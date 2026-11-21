"""Capture same-request RAG evidence and compare retrieval configurations with RAGAS."""

import argparse
import asyncio
import getpass
import hashlib
import json
import math
import os
import re
import statistics
import time
import unicodedata
from pathlib import Path
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
CASES = Path(__file__).with_name("questions.json")
PROMPT_DOCUMENT = re.compile(r"<content\b[^>]*>(.*?)</content>", re.DOTALL)
VERSION = re.compile(r"^store-(.+?)\.[0-9a-f]{16}\.md$")
CITATION = re.compile(r"\[(\d+)\]\(#cite-\1\)")


def request(base, path, token=None, body=None):
    headers = {"Authorization": token} if token else {}
    if body is not None:
        body = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    return urlopen(Request(base + path, data=body, headers=headers), timeout=180)


def result(base, path, token=None, body=None):
    with request(base, path, token, body) as response:
        data = json.load(response)
    if str(data.get("code")) != "0":
        raise RuntimeError(f"API {path.split('?')[0]} returned {data.get('code')}: {data.get('message', '')}")
    return data["data"]


def read_answer(base, question, token):
    deltas, sources = [], []
    first_ms, evidence = None, None
    started = time.monotonic()
    query = urlencode({"question": question, "evaluation": "true"})
    with request(base, "/rag/v3/chat?" + query, token) as response:
        if response.headers.get_content_type() != "text/event-stream":
            raise RuntimeError("The chat endpoint did not return an SSE stream.")
        event, completed = "", False
        for raw in response:
            line = raw.decode().rstrip("\r\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                if event in {"error", "reject", "cancel"}:
                    raise RuntimeError(f"Chat ended with SSE event {event}.")
                if event == "done":
                    completed = True
                elif event == "message":
                    payload = json.loads(line[5:])
                    if payload.get("type") != "think" and payload.get("delta"):
                        if first_ms is None:
                            first_ms = round((time.monotonic() - started) * 1000)
                        deltas.append(payload["delta"])
                elif event == "evaluation":
                    evidence = json.loads(line[5:])
                elif event == "finish":
                    sources = json.loads(line[5:]).get("sources") or []
    if not completed or not deltas or evidence is None:
        raise RuntimeError("The stream did not finish with answer and same-request evidence.")
    return {
        "answer": "".join(deltas), "sources": sources,
        "retrieved": [chunk for chunk in evidence["chunks"]],
        "prompt_context": evidence["promptContext"],
        "retrieval_ms": evidence["retrievalMs"], "first_ms": first_ms,
        "total_ms": round((time.monotonic() - started) * 1000),
    }


def stable_document(name):
    if not name:
        return None
    match = VERSION.fullmatch(name)
    return match[1] if match else None


def normalized(text):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", text).lower())


def ranking(predicted, gold):
    keys = ("hit_at_1", "hit_at_5", "hit_at_10", "mrr_at_10", "ndcg_at_10", "recall_at_10")
    if not gold:
        return dict.fromkeys(keys)
    ranks = [rank for rank, doc in enumerate(predicted[:10], start=1) if doc in gold]
    dcg = sum(1 / math.log2(rank + 1) for rank in ranks)
    ideal = sum(1 / math.log2(rank + 1) for rank in range(1, min(10, len(gold)) + 1))
    return {
        "hit_at_1": int(1 in ranks), "hit_at_5": int(any(rank <= 5 for rank in ranks)),
        "hit_at_10": int(bool(ranks)), "mrr_at_10": 1 / ranks[0] if ranks else 0,
        "ndcg_at_10": dcg / ideal, "recall_at_10": len(ranks) / len(gold),
    }


def deterministic_scores(case, captured):
    gold = set(case["sources"])
    ranked = list(dict.fromkeys(
        doc for chunk in captured["retrieved"] if (doc := stable_document(chunk.get("docName")))
    ))
    source_index = {str(source["index"]): stable_document(source.get("docName")) for source in captured["sources"]}
    indexes = set(CITATION.findall(captured["answer"]))
    matches = sum(source_index.get(index) in gold for index in indexes)
    cited_ids = {source_index.get(index) for index in indexes}
    answer = normalized(captured["answer"])
    terms = [normalized(term) in answer for term in case["terms"]]
    return ranking(ranked, gold) | {
        "required_term_coverage": sum(terms) / len(terms) if terms else None,
        "all_required_terms": int(all(terms)) if terms else None,
        "citation_precision": matches / len(indexes) if indexes else (0 if gold else None),
        "citation_recall": len(cited_ids & gold) / len(gold) if gold else None,
        "abstention": (int(any(word in answer for word in ("无法", "未找到", "未检索", "不足", "不能确认", "没有")))
                       if case["expected_behavior"] == "unknown" else None),
        "clarification": (int(any(word in answer for word in ("请问", "请提供", "请确认", "哪一款", "具体型号")))
                          if case["expected_behavior"] == "clarify" else None),
    }


class RagasScores:
    def __init__(self):
        os.environ.setdefault("RAGAS_DO_NOT_TRACK", "true")
        from openai import AsyncOpenAI
        from ragas.embeddings.base import embedding_factory
        from ragas.llms import llm_factory
        from ragas.metrics.collections import (
            AnswerRelevancy, ContextEntityRecall, ContextPrecision, ContextRecall,
            FactualCorrectness, Faithfulness,
        )

        model_client = AsyncOpenAI(api_key=os.environ["STORE_EVAL_JUDGE_KEY"],
                                   base_url=os.environ["STORE_EVAL_JUDGE_BASE"], timeout=75, max_retries=1)
        embedding_client = AsyncOpenAI(api_key=os.environ["STORE_EVAL_EMBEDDING_KEY"],
                                       base_url=os.environ["STORE_EVAL_EMBEDDING_BASE"], timeout=45, max_retries=1)
        llm = llm_factory(os.environ["STORE_EVAL_JUDGE_MODEL"], client=model_client, max_tokens=4096)
        embeddings = embedding_factory("openai", model=os.environ["STORE_EVAL_EMBEDDING_MODEL"],
                                       client=embedding_client)
        self.judge_slots = asyncio.Semaphore(6)
        self.specs = {
            "faithfulness": (Faithfulness(llm=llm), ("user_input", "response", "retrieved_contexts")),
            "response_relevancy": (AnswerRelevancy(llm=llm, embeddings=embeddings), ("user_input", "response")),
            "context_precision": (ContextPrecision(llm=llm), ("user_input", "reference", "retrieved_contexts")),
            "context_recall": (ContextRecall(llm=llm), ("user_input", "reference", "retrieved_contexts")),
            "context_entity_recall": (ContextEntityRecall(llm=llm), ("reference", "retrieved_contexts")),
            "factual_correctness": (FactualCorrectness(llm=llm), ("response", "reference")),
        }

    async def score(self, case, captured, only=None):
        context = captured["prompt_context"]
        contexts = [part.strip() for part in PROMPT_DOCUMENT.findall(context) if part.strip()]
        values = {"user_input": case["question"], "reference": case["expected"],
                  "response": captured["answer"], "retrieved_contexts": contexts}
        async def measure(name, metric, fields):
            if "retrieved_contexts" in fields and not contexts:
                return name, 0.0 if case["sources"] and name != "faithfulness" else None, None
            try:
                async with self.judge_slots:
                    value = float((await metric.ascore(**{key: values[key] for key in fields})).value)
                if name == "faithfulness" and math.isnan(value):
                    return name, None, None
                if not math.isfinite(value):
                    raise ValueError("metric returned a nonfinite result")
                return name, value, None
            except Exception as exc:
                return name, None, type(exc).__name__
        measurements = await asyncio.gather(*(measure(name, metric, fields)
                                              for name, (metric, fields) in self.specs.items()
                                              if only is None or name in only))
        return ({name: value for name, value, _ in measurements},
                {name: error for name, _, error in measurements if error})


def corrections_for(path):
    if path is None:
        return {}
    entries = json.loads(path.read_text(encoding="utf-8"))
    validated = {}
    for correction in entries:
        key = (correction["mode"], correction["id"], correction["metric"])
        if key in validated or not correction["reason"].strip() or not 0 <= correction["score"] <= 1:
            raise ValueError(f"Invalid or duplicate correction: {key}")
        validated[key] = correction
    return validated


async def capture_mode(
    mode, base, username, password, cases, output, scorer, corrections, concurrency=1, previous=None
):
    login = result(base, "/auth/login", body={"username": username, "password": password})
    if login.get("role") != "admin":
        raise RuntimeError("An administrator account is required.")
    token = login["token"]
    slots = asyncio.Semaphore(concurrency)
    captured_rows = []
    retry_records = previous or {}

    async def capture_case(case):
        row = {"id": case["id"], "category": case["category"]}
        async with slots:
            try:
                saved = retry_records.get(case["id"])
                if saved and not saved.get("error"):
                    recorded = {key: saved[key] for key in
                                ("answer", "sources", "retrieved", "prompt_context", "retrieval_ms", "first_ms", "total_ms")}
                    retry_metrics = saved.get("metric_errors", {})
                    scores = dict(saved["raw_scores"])
                    if retry_metrics:
                        ragas_scores, errors = await scorer.score(case, recorded, only=retry_metrics)
                        scores.update(ragas_scores)
                        row["rescored_metrics"] = sorted(retry_metrics)
                    else:
                        errors = {}
                else:
                    recorded = await asyncio.to_thread(read_answer, base, case["question"], token)
                    ragas_scores, errors = await scorer.score(case, recorded)
                    scores = deterministic_scores(case, recorded) | ragas_scores
                adjusted = dict(scores)
                fixes = [correction for (label, case_id, _), correction in corrections.items()
                         if label == mode and case_id == case["id"]]
                for correction in fixes:
                    if correction["metric"] not in adjusted:
                        raise ValueError(f"Unknown correction metric: {correction['metric']}")
                    adjusted[correction["metric"]] = correction["score"]
                row |= recorded | {"raw_scores": scores, "scores": adjusted, "metric_errors": errors,
                                   "corrections": fixes}
            except (OSError, ValueError, RuntimeError) as exc:
                row["error"] = str(exc)
        return row

    try:
        active_output = output.with_suffix(".resume.jsonl") if previous is not None else output
        with active_output.open("w", encoding="utf-8") as target:
            tasks = [asyncio.create_task(capture_case(case)) for case in cases]
            try:
                for task in tasks:
                    row = await task
                    captured_rows.append(row)
                    target.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")
                    target.flush()
                    print(f"{mode}/{row['id']}: {'error' if row.get('error') or row.get('metric_errors') else 'captured'}")
            finally:
                for task in tasks:
                    if not task.done():
                        task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
        if previous is not None:
            active_output.replace(output)
    finally:
        result(base, "/auth/logout", token=token, body={})
    return captured_rows


def percentile(values, fraction):
    samples = sorted(values)
    return samples[max(0, math.ceil(fraction * len(samples)) - 1)] if samples else None


def summarize(rows):
    valid = [row for row in rows if "error" not in row]
    metrics = sorted({name for row in valid for name in row["scores"]})
    columns = {}
    for name in metrics:
        values = [row["scores"][name] for row in valid if row["scores"].get(name) is not None]
        columns[name] = {"mean": statistics.mean(values) if values else None, "n": len(values)}
    for key in ("first_ms", "total_ms"):
        values = [row[key] for row in valid if row[key] is not None]
        for label, probability in (("p50", 0.5), ("p95", 0.95)):
            columns[f"{key}_{label}"] = {"value": percentile(values, probability), "n": len(values)}
    columns["mean_retrieval_ms"] = {"value": statistics.mean(row["retrieval_ms"] for row in valid)
                                     if valid else None, "n": len(valid)}
    columns["request_error_rate"] = {"value": (len(rows) - len(valid)) / len(rows), "n": len(rows)}
    return columns


def diff_rows(baseline, variant):
    base_by_id = {row["id"]: row for row in baseline}
    result_rows = []
    for row in variant:
        reference = base_by_id[row["id"]]
        before, after = reference.get("scores", {}), row.get("scores", {})
        delta = {metric: score - before[metric] for metric, score in after.items()
                 if score is not None and before.get(metric) is not None}
        result_rows.append({"id": row["id"], "scores_delta": delta})
    return result_rows


MODES = ("vector", "keyword", "hybrid")


def read_rows(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def fingerprint(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def metadata(args):
    return {
        "cases_sha256": fingerprint(args.cases),
        "catalogue_sha256": fingerprint(args.catalogue),
        "judge_model": os.environ.get("STORE_EVAL_JUDGE_MODEL"),
        "embedding_model": os.environ.get("STORE_EVAL_EMBEDDING_MODEL"),
        "concurrency": getattr(args, "concurrency", 1),
    }


def compare(args, cases):
    all_meta = [json.loads((args.output / f"{mode}.meta.json").read_text(encoding="utf-8")) for mode in MODES]
    if any(item != all_meta[0] for item in all_meta[1:]):
        raise ValueError("Catalogue, question set or evaluator model differs between configurations.")
    if all_meta[0]["cases_sha256"] != fingerprint(args.cases):
        raise ValueError("The captured question set differs from the current fixture.")
    corrections = corrections_for(args.corrections)
    modes = {}
    for mode in MODES:
        rows = read_rows(args.output / f"{mode}.jsonl")
        if [row["id"] for row in rows] != [case["id"] for case in cases]:
            raise ValueError(f"Incomplete or reordered case set: {mode}")
        for row in rows:
            if "raw_scores" not in row:
                continue
            row["scores"] = dict(row["raw_scores"])
            for metric in row["scores"]:
                fix = corrections.get((mode, row["id"], metric))
                if fix:
                    row["scores"][metric] = fix["score"]
        modes[mode] = rows
    report = {
        "inputs": all_meta[0], "corrections": list(corrections.values()),
        "modes": {mode: summarize(rows) for mode, rows in modes.items()},
        "categories": {
            category: {
                mode: summarize([row for row in rows if row["category"] == category])
                for mode, rows in modes.items()
            }
            for category in sorted({case["category"] for case in cases})
        },
        "diff_vs_vector": {mode: diff_rows(modes["vector"], rows)
                           for mode, rows in modes.items() if mode != "vector"},
    }
    (args.output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if any("error" in row or row.get("metric_errors") for rows in modes.values() for row in rows):
        raise SystemExit("Evaluation contains errors. Inspect the mode logs before using the summary.")


async def run(args):
    cases = json.loads(args.cases.read_text(encoding="utf-8"))
    if len(cases) < 60 or len({case["id"] for case in cases}) != len(cases):
        raise ValueError("The fixture must contain at least 60 uniquely named cases.")
    if args.compare:
        compare(args, cases)
        return
    if urlsplit(args.base).hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("Evaluation targets must be local instances.")
    password = getpass.getpass("Store agent administrator password: ")
    args.output.mkdir(parents=True, exist_ok=True)
    output = args.output / f"{args.mode}.jsonl"
    config = args.output / f"{args.mode}.meta.json"
    details = metadata(args)
    previous = None
    if args.resume:
        if not output.is_file() or not config.is_file():
            raise ValueError("Resume needs the earlier run log and metadata.")
        if json.loads(config.read_text(encoding="utf-8")) != details:
            raise ValueError("Resume requires the same questions, catalogue, models and concurrency.")
        previous = {row["id"]: row for row in read_rows(output)}
    scorer = RagasScores()
    rows = await capture_mode(args.mode, args.base.rstrip("/"), args.user, password, cases,
                              output, scorer, corrections_for(args.corrections), args.concurrency, previous)
    config.write_text(json.dumps(details, indent=2), encoding="utf-8")
    if any("error" in row or row.get("metric_errors") for row in rows):
        raise SystemExit("This run contains errors. Inspect the case log.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--mode", choices=MODES)
    action.add_argument("--compare", action="store_true")
    parser.add_argument("--base", default="http://127.0.0.1:29090/api/store-agent")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--cases", type=Path, default=CASES)
    parser.add_argument("--catalogue", type=Path, default=ROOT / "resources/store-catalog/records.json")
    parser.add_argument("--corrections", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "local/logs/evaluation")
    parser.add_argument("--concurrency", type=int, choices=range(1, 5), default=1)
    parser.add_argument("--resume", action="store_true")
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
