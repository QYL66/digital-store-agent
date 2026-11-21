"""Validate a curated official-source snapshot and produce idempotent import artifacts."""

from __future__ import annotations

import argparse
import base64
from collections import Counter
from datetime import date
import hashlib
import json
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit

KINDS = {"phone", "accessory", "policy", "fault", "service_center"}
PHONE_FIELDS = {"屏幕", "芯片", "电池", "有线充电", "无线充电", "快充协议", "销售规格"}
ROOT = Path(__file__).resolve().parents[1]


def official_source(url: str) -> bool:
    parsed = urlsplit(url)
    hostname = parsed.hostname or ""
    return (parsed.scheme == "https" and (hostname == "mi.com" or hostname.endswith(".mi.com"))
            and not parsed.username and not parsed.password)


def read_snapshot(path: Path) -> list[dict]:
    rows = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(rows, list) or not rows:
        raise ValueError("Snapshot must contain a nonempty list of records.")
    ids = set()
    for row in rows:
        key = row["id"]
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", key) or key in ids:
            raise ValueError(f"Invalid or duplicate stable ID: {key}")
        ids.add(key)
        if row["kind"] not in KINDS or row["region"] != "CN" or not official_source(row["source_url"]):
            raise ValueError(f"Invalid kind, region or official source: {key}")
        date.fromisoformat(row["checked_on"])
        details = row["details"]
        if not row["name"].strip() or not row["series"].strip() or not details.get("fields"):
            raise ValueError(f"Missing catalogue fields: {key}")
        if row["kind"] == "phone" and details.get("brand") not in {"Xiaomi", "REDMI"}:
            raise ValueError(f"Invalid phone brand: {key}")
        if any(not isinstance(value, str) or not value.strip() for value in details["fields"].values()):
            raise ValueError(f"Invalid field value: {key}")
    phones = {row["id"] for row in rows if row["kind"] == "phone"}
    for row in rows:
        for item in row["details"].get("compatibility", []):
            target = item.get("phoneId")
            if bool(target) == bool(item.get("phoneSeries")) or (target and target not in phones):
                raise ValueError(f"Invalid compatibility reference: {row['id']}")
            if not item.get("statement", "").strip():
                raise ValueError(f"Missing evidence statement: {row['id']}")
    return sorted(rows, key=lambda row: row["id"])


def sql_for(rows: list[dict], emit_effective_snapshot: bool = False) -> str:
    payload = base64.b64encode(json.dumps(rows, ensure_ascii=False).encode()).decode()
    sql = f"""INSERT INTO store_catalog (id, kind, name, series, source_url, region, checked_on, details)
SELECT id, kind, name, series, source_url, region, checked_on, details
FROM jsonb_to_recordset(convert_from(decode('{payload}', 'base64'), 'UTF8')::jsonb)
    AS r(id text, kind text, name text, series text, source_url text, region text, checked_on date, details jsonb)
ON CONFLICT (id) DO UPDATE SET
    kind = EXCLUDED.kind, name = EXCLUDED.name, series = EXCLUDED.series,
    source_url = EXCLUDED.source_url, region = EXCLUDED.region,
    checked_on = EXCLUDED.checked_on, details = EXCLUDED.details
WHERE EXCLUDED.checked_on >= store_catalog.checked_on
    AND (store_catalog.kind, store_catalog.name, store_catalog.series, store_catalog.source_url,
         store_catalog.region, store_catalog.checked_on, store_catalog.details)
        IS DISTINCT FROM
        (EXCLUDED.kind, EXCLUDED.name, EXCLUDED.series, EXCLUDED.source_url,
         EXCLUDED.region, EXCLUDED.checked_on, EXCLUDED.details);
"""
    if not emit_effective_snapshot:
        return sql

    doc_ids = [row["id"] for row in rows if row["kind"] != "service_center"]
    encoded_ids = base64.b64encode(json.dumps(doc_ids).encode()).decode()
    return sql + f"""SELECT replace(encode(convert_to(
    COALESCE(jsonb_agg(to_jsonb(effective) ORDER BY effective.id), '[]'::jsonb)::text,
    'UTF8'), 'base64'), chr(10), '')
FROM (
    SELECT id, kind, name, series, source_url, region, checked_on, details
    FROM store_catalog
    WHERE id IN (
        SELECT jsonb_array_elements_text(convert_from(decode('{encoded_ids}', 'base64'), 'UTF8')::jsonb)
    )
) AS effective;
"""


def effective_document_rows(requested: list[dict], effective_path: Path) -> list[dict]:
    requested_docs = {row["id"]: row for row in requested if row["kind"] != "service_center"}
    effective = json.loads(effective_path.read_text(encoding="utf-8"))
    selected = {row["id"]: row for row in effective}
    if len(selected) != len(effective) or selected.keys() != requested_docs.keys():
        raise ValueError("Effective catalogue export does not match the requested document IDs.")
    # The original order keeps existing documents byte-stable when PostgreSQL accepted the input.
    return [row if selected[key] == row else selected[key] for key, row in requested_docs.items()]


def write_documents(rows: list[dict], target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    for row in rows:
        if row["kind"] == "service_center":
            continue
        details = row["details"]
        text = [f"# {row['name']}", "", f"分类：{row['series']}",
                f"范围：中国大陆。核对日期：{row['checked_on']}",
                f"资料来源：{row['source_url']}", ""]
        if details.get("aliases"):
            text.append("别名：" + "、".join(details["aliases"]))
        text.extend(f"- {key}：{value}" for key, value in details["fields"].items())
        text.extend(f"- 兼容证据（{item.get('phoneSeries') or item['phoneId']}）：{item['statement']}"
                    for item in details.get("compatibility", []))
        content = "\n".join(text) + "\n"
        (target / f"store-{row['id']}.md").write_text(content, encoding="utf-8")


def coverage(rows: list[dict], source_index: Path, listing_index: Path) -> dict:
    categories = Counter(row["kind"] for row in rows)
    groups = Counter(f"{row['kind']} / {row['series']}" for row in rows)
    missing = {row["id"]: sorted(PHONE_FIELDS.difference(row["details"]["fields"]))
               for row in rows if row["kind"] == "phone"}
    return {
        "snapshot_sha256": hashlib.sha256(json.dumps(rows, sort_keys=True, ensure_ascii=False).encode()).hexdigest(),
        "records": dict(sorted(categories.items())),
        "groups": dict(sorted(groups.items())),
        "missing_phone_fields": {key: value for key, value in missing.items() if value},
        "sources": json.loads(source_index.read_text(encoding="utf-8")),
        "listing_scan": listing_coverage(rows, listing_index),
    }


def listing_coverage(rows: list[dict], listing_index: Path) -> list[dict]:
    if not listing_index.is_file():
        return []
    ids = {row["id"] for row in rows}
    scan = json.loads(listing_index.read_text(encoding="utf-8"))
    report = []
    for group in scan:
        name = group["group"]
        pages = group["page_result_counts"]
        expected = group["pages_expected"]
        if not official_source(group["url"]):
            raise ValueError(f"Invalid official listing source: {name}")
        if type(expected) is not int or expected < 1 or len(pages) > expected:
            raise ValueError(f"Invalid planned page count: {name}")
        if any(type(count) is not int or count < 0 for count in pages):
            raise ValueError(f"Invalid page result count: {name}")
        discovered = set(group["matched_record_ids"])
        report.append({
            "group": name,
            "url": group["url"],
            "checked_on": date.fromisoformat(group["checked_on"]).isoformat(),
            "pages_expected": expected,
            "pages_checked": len(pages),
            "result_entries": sum(pages),
            "distinct_catalog_matches": len(discovered),
            "matched_records_in_snapshot": len(discovered & ids),
        })
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("--sql", type=Path)
    parser.add_argument("--docs", type=Path)
    parser.add_argument("--emit-effective-snapshot", action="store_true")
    parser.add_argument("--effective-snapshot", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--centers", type=Path, default=ROOT / "local/logs/service-centers-snapshot.json")
    parser.add_argument("--source-index", type=Path, default=ROOT / "resources/store-catalog/sources.json")
    parser.add_argument("--listing-index", type=Path, default=ROOT / "resources/store-catalog/listing-scan.json")
    args = parser.parse_args()
    if args.emit_effective_snapshot and not args.sql:
        parser.error("--emit-effective-snapshot requires --sql")
    if args.effective_snapshot and not args.docs:
        parser.error("--effective-snapshot requires --docs")
    rows = read_snapshot(args.snapshot)
    centers_added = 0
    if args.centers.is_file():
        ids = {row["id"] for row in rows}
        raw_centers = json.loads(args.centers.read_text(encoding="utf-8"))
        if raw_centers:
            for row in read_snapshot(args.centers):
                if row["kind"] != "service_center":
                    raise ValueError("Centre snapshot contains other catalogue types.")
                if row["id"] not in ids:
                    rows.append(row)
                    centers_added += 1
        rows.sort(key=lambda row: row["id"])
    if args.sql:
        args.sql.write_text(sql_for(rows, args.emit_effective_snapshot), encoding="utf-8")
    if args.docs:
        documents = effective_document_rows(rows, args.effective_snapshot) if args.effective_snapshot else rows
        write_documents(documents, args.docs)
    details = coverage(rows, args.source_index, args.listing_index)
    details["local_service_centers_imported"] = centers_added
    centers_coverage = args.centers.with_name("service-centers-coverage.json")
    if centers_coverage.is_file():
        details["service_center_scan"] = json.loads(centers_coverage.read_text(encoding="utf-8"))
    report = json.dumps(details, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=args.report.parent,
                                         prefix=args.report.name + ".", delete=False) as temp:
            temp_path = Path(temp.name)
            temp.write(report)
        try:
            temp_path.replace(args.report)
        finally:
            temp_path.unlink(missing_ok=True)
    else:
        print(report)


if __name__ == "__main__":
    main()
