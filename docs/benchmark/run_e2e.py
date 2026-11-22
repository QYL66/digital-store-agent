"""Capture local Agent SSE tests; final fact and citation scores require human review."""

import argparse
import csv
from getpass import getpass
import json
import os
from pathlib import Path
from statistics import median, quantiles
import time

import requests


DATA = Path(__file__).resolve().parent / "eval_dataset_50.json"
MAX_CALLS = 65
METRICS = ("clarify_ok", "tool_match", "fact_pass", "citation_ok", "power_misattributed")


def ms(start):
    return round((time.perf_counter() - start) * 1000, 3)


def events(response):
    kind, data = "message", []
    for line in response.iter_lines(chunk_size=1, decode_unicode=True):
        if not line:
            if data:
                yield kind, json.loads("\n".join(data))
            kind, data = "message", []
        elif line.startswith("event:"):
            kind = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].strip())


def observe(session, base, question, conversation):
    started = time.perf_counter()
    result = {"status": "FAILED", "sse_first_event_ms": None, "sse_first_progress_ms": None,
              "sse_first_text_ms": None, "answer": "", "tools": [], "notes": ""}
    tools = {}
    params = {"question": question}
    if conversation:
        params["conversationId"] = conversation
    try:
        with session.get(base + "/agent/v1/chat", params=params, stream=True,
                         timeout=(5, 100), allow_redirects=False) as response:
            if response.status_code != 200 or "text/event-stream" not in response.headers.get("Content-Type", ""):
                result["notes"] = f"HTTP {response.status_code} or wrong response type"
                return result, conversation
            response.encoding = "utf-8"
            finished = False
            for kind, payload in events(response):
                event_ms = ms(started)
                if result["sse_first_event_ms"] is None:
                    result["sse_first_event_ms"] = event_ms
                    result["first_event_type"] = kind
                if not isinstance(payload, dict):
                    continue
                if kind == "meta":
                    conversation = payload.get("conversationId", conversation)
                if kind in ("hint", "message", "block") and result["sse_first_progress_ms"] is None:
                    result["sse_first_progress_ms"] = event_ms
                if kind == "message" and payload.get("type") == "answer":
                    delta = payload.get("delta") or ""
                    if delta.strip() and result["sse_first_text_ms"] is None:
                        result["sse_first_text_ms"] = event_ms
                    result["answer"] += delta
                if kind == "block" and payload.get("kind") == "tool":
                    key = payload.get("toolCallId")
                    if key:
                        tools[key] = {field: payload.get(field) for field in
                                      ("name", "status", "ok", "durationMs", "durationSource")}
                if kind == "finish":
                    finished = payload.get("messageStatus") in ("NORMAL", None)
                if kind == "confirm":
                    result["notes"] = "Unexpected write confirmation for a read-only case"
                if kind == "done":
                    if finished and result["answer"].strip():
                        result["status"] = "OK"
                    break
            result["tools"] = list(tools.values())
            if result["status"] != "OK" and not result["notes"]:
                result["notes"] = "Completed answer not observed"
    except (requests.RequestException, ValueError) as error:
        result["notes"] = type(error).__name__
    return result, conversation


def summarize(rows):
    slices = {}
    for group, matches in (("0-1", lambda n: n <= 1), ("2+", lambda n: n >= 2)):
        selected = [row for row in rows if row["status"] == "OK" and matches(row["tool_count"])]
        times = {}
        for name in ("sse_first_event_ms", "sse_first_progress_ms", "sse_first_text_ms"):
            values = [row[name] for row in selected if row[name] is not None]
            quartiles = quantiles(values, n=4, method="inclusive") if len(values) > 1 else None
            times[name] = {"n": len(values), "p50": median(values) if values else None,
                           "q1": quartiles[0] if quartiles else None,
                           "q3": quartiles[2] if quartiles else None}
        slices[group] = times
    return {"rows": len(rows), "ok": sum(row["status"] == "OK" for row in rows),
            "status_counts": {status: sum(row["status"] == status for row in rows)
                              for status in ("OK", "FAILED", "SKIPPED", "NOT_RUN")},
            "timing_by_observed_tool_calls": slices}


def blank_row(case, turn, note):
    return {"case_id": case["case_id"], "category": case["category"], "turn_index": turn["turn_index"],
            "status": "NOT_RUN", **dict.fromkeys(METRICS),
            "risk": int(case["power_misattribution_risk"]), "tool_count": 0, "tools": None,
            "local_mcp_tool_ms": None,
            "sse_first_event_ms": None, "sse_first_progress_ms": None, "sse_first_text_ms": None,
            "first_event_type": None, "notes": note}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--port", type=int, default=29090)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--unavailable", action="store_true", help="Write a clearly unscored roster without making requests")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    cases = json.loads(DATA.read_text(encoding="utf-8"))
    rows = [blank_row(case, turn, "Model service not available") for case in cases for turn in case["turns"]]
    if not args.unavailable:
        base = f"http://127.0.0.1:{args.port}/api/store-agent"
        session = requests.Session()
        session.trust_env = False
        password = os.environ.get("STORE_AGENT_EVAL_PASSWORD") or getpass("Local Store Agent password: ")
        login = session.post(base + "/auth/login", json={"username": args.username, "password": password},
                             timeout=10, allow_redirects=False)
        token = (login.json().get("data") or {}).get("token")
        if not token:
            raise RuntimeError("Local evaluation login failed")
        session.headers.update({"Authorization": token})
        calls = 0
        consecutive_failures = 0
        index = 0
        with (args.out / "e2e_local_raw.jsonl").open("x", encoding="utf-8") as raw:
            for query in ("小米商城优惠券能叠加吗？", "红米K90的典型电池容量是多少？"):
                calls += 1
                result, _ = observe(session, base, query, None)
                raw.write(json.dumps({"case_id": "WARMUP", "turn": calls, **result},
                                     ensure_ascii=False) + "\n")
                raw.flush()
                consecutive_failures = 0 if result["status"] == "OK" else consecutive_failures + 1
            for case in cases:
                conversation = None
                for turn in case["turns"]:
                    row = rows[index]
                    index += 1
                    if calls >= MAX_CALLS or consecutive_failures >= 3:
                        row["notes"] = "Run stopped after consecutive failures or the request cap"
                        continue
                    if turn["turn_index"] > 1 and not conversation:
                        row.update(status="SKIPPED", notes="No conversation ID from turn one")
                        continue
                    calls += 1
                    result, conversation = observe(session, base, turn["user_query"], conversation)
                    raw.write(json.dumps({"case_id": row["case_id"], "turn": row["turn_index"],
                                         **result}, ensure_ascii=False) + "\n")
                    raw.flush()
                    consecutive_failures = 0 if result["status"] == "OK" else consecutive_failures + 1
                    tool_calls = result["tools"]
                    row.update({field: result.get(field) for field in (
                        "status", "sse_first_event_ms", "sse_first_progress_ms", "sse_first_text_ms",
                        "first_event_type", "notes")})
                    row["tool_count"] = len(tool_calls)
                    row["tools"] = json.dumps(tool_calls, ensure_ascii=False)
                    row["local_mcp_tool_ms"] = "|".join(str(t["durationMs"]) for t in tool_calls
                        if "store_" in str(t["name"]) and t["durationSource"] == "tool"
                        and t["durationMs"] is not None) or None
                    print(row["case_id"], row["turn_index"], row["status"], flush=True)
    with (args.out / "e2e_eval_results.csv").open("x", newline="", encoding="utf-8-sig") as output:
        writer = csv.DictWriter(output, fieldnames=rows[0])
        writer.writeheader()
        writer.writerows(rows)
    (args.out / "e2e_summary.json").write_text(
        json.dumps(summarize(rows), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
