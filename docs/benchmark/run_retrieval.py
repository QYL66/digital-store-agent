"""Run the three local retrieval variants on the frozen public-catalogue benchmark."""

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
from statistics import mean
import time

import psycopg

from build_dataset import CATALOGUE, DESTINATION, RECORDS, ROOT, chunk


VEC_MODEL = "bge-small-zh-v1.5"
RERANK_MODEL = "bge-reranker-base"
VARIANTS = ("vec_only", "vec_rerank", "pg_hybrid_rrf_rerank")
POOL = 20
FINAL = 5
RRF_K = 20
COLLECTION = "store-eval"
MIGRATION = ROOT / "resources/database/upgrades/store-agent/pg-keyword.sql"
KEYWORD_SQL = ROOT / "rag/src/main/resources/keyword/search-pg.sql"


def milliseconds(start):
    return round((time.perf_counter() - start) * 1000, 3)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def check_lexical(db, keyword):
    expected = ("支持 90w 澎湃秒充", "小米 14pro 参数", "支持 90w 澎湃秒充")
    got = db.execute("SELECT store_keyword_text(%s),store_keyword_text(%s),store_keyword_text(%s)",
                     ("支持90W澎湃秒充", "小米14Pro参数", "支持90 W澎湃秒充")).fetchone()
    assert got == expected, (got, expected)
    temp = "test-lexical"
    other = "test-other"
    with db.transaction():
        for identifier, scope, content in [
            ("test-pro", temp, "小米14Pro支持90W澎湃秒充，" + "屏幕与影像参数。" * 35),
            ("test-ultra", temp, "小米14Ultra支持120W充电。"),
            ("test-other", other, "小米14Pro支持90W澎湃秒充。"),
        ]:
            db.execute("INSERT INTO t_knowledge_vector(id,collection_name,content) VALUES(%s,%s,%s)",
                       (identifier, scope, content))
        short = [row[0] for row in db.execute(keyword, ("澎湃秒充", temp, POOL))]
        model = [row[0] for row in db.execute(keyword, ("14 Pro 90 W", temp, POOL))]
        assert short == ["test-pro"], short
        assert model[0] == "test-pro" and "test-other" not in model, model
        db.execute("UPDATE t_knowledge_vector SET content = '已更正为小米18Ultra' WHERE id='test-pro'")
        assert not list(db.execute(keyword, ("90 W", temp, POOL)))
        db.execute("DELETE FROM t_knowledge_vector WHERE id='test-ultra'")
        assert not list(db.execute(keyword, ("120w", temp, POOL)))
        db.execute("DELETE FROM t_knowledge_vector WHERE collection_name IN (%s,%s)", (temp, other))
    print("Verified attached tokens, long-chunk Chinese search, filtering, update and delete", flush=True)


def check_existing_rows_migrate(db, migration, keyword):
    with db.transaction(force_rollback=True):
        db.execute("CREATE SCHEMA store_eval_upgrade")
        db.execute("SET LOCAL search_path TO store_eval_upgrade,public")
        db.execute("CREATE TABLE t_knowledge_vector("
                   "id varchar(20) PRIMARY KEY, collection_name varchar(64) NOT NULL, "
                   "content text, metadata jsonb, embedding vector(1536))")
        db.execute("INSERT INTO t_knowledge_vector(id,collection_name,content) "
                   "VALUES('previous','old','旧记录支持90W澎湃秒充')")
        db.execute(migration)
        db.execute(migration)
        assert [r[0] for r in db.execute(keyword, ("90W", "old", POOL))] == ["previous"]
        indexes = {r[0] for r in db.execute("SELECT indexname FROM pg_indexes "
                                             "WHERE schemaname='store_eval_upgrade'")}
        assert {"idx_store_kw_lexemes", "idx_store_kw_trigrams"}.issubset(indexes)
        db.execute("SET LOCAL enable_seqscan=off")
        for expression, expected in (
            ("to_tsvector('simple', store_keyword_text(content)) @@ to_tsquery('simple','90w')",
             "idx_store_kw_lexemes"),
            ("store_keyword_text(content) %> '澎湃秒充'", "idx_store_kw_trigrams"),
        ):
            plan = "\n".join(r[0] for r in db.execute(
                "EXPLAIN (COSTS OFF) SELECT id FROM t_knowledge_vector WHERE " + expression))
            assert expected in plan, plan
    print("Verified idempotent keyword migration on an existing 1536-dimensional table", flush=True)


def get_models(models, threads):
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    import torch
    from transformers import AutoModel, AutoModelForSequenceClassification, AutoTokenizer

    torch.set_num_threads(threads)
    encoder = AutoModel.from_pretrained(models / VEC_MODEL, use_safetensors=True).eval()
    embed_tokenizer = AutoTokenizer.from_pretrained(models / VEC_MODEL)
    reranker = AutoModelForSequenceClassification.from_pretrained(
        models / RERANK_MODEL, use_safetensors=True).eval()
    rank_tokenizer = AutoTokenizer.from_pretrained(models / RERANK_MODEL)

    def embed(values):
        matrix = []
        for offset in range(0, len(values), 16):
            model_inputs = embed_tokenizer(values[offset:offset + 16], return_tensors="pt",
                                          padding=True, truncation=True, max_length=384)
            with torch.inference_mode():
                vectors = encoder(**model_inputs).last_hidden_state[:, 0]
                matrix.extend(torch.nn.functional.normalize(vectors, dim=1).tolist())
        return matrix

    def rerank(query, candidates, contents):
        included = [contents[identifier] for identifier in candidates]
        scores = []
        for offset in range(0, len(included), 8):
            subset = included[offset:offset + 8]
            model_inputs = rank_tokenizer([query] * len(subset), subset, return_tensors="pt",
                                          padding=True, truncation=True, max_length=384)
            with torch.inference_mode():
                scores.extend(reranker(**model_inputs).logits.flatten().tolist())
        return sorted(candidates, key=dict(zip(candidates, scores, strict=True)).__getitem__, reverse=True)

    return embed, rerank


def evaluate(db, dataset, embed, rerank, keyword):
    chunks = {row["id"]: row["text"] for row in map(chunk, RECORDS.values())}
    db.execute("DELETE FROM t_knowledge_vector WHERE collection_name=%s", (COLLECTION,))
    chunk_ids = sorted(chunks)
    vectors = embed([chunks[key] for key in chunk_ids])
    with db.cursor() as cur:
        cur.executemany("INSERT INTO t_knowledge_vector(id,collection_name,content,embedding) "
                        "VALUES(%s,%s,%s,%s::vector)",
                        [(key, COLLECTION, chunks[key], json.dumps(vector))
                         for key, vector in zip(chunk_ids, vectors, strict=True)])
    db.execute("ANALYZE t_knowledge_vector")
    rerank("手机参数", chunk_ids[:2], chunks)
    observations = []
    for case in dataset:
        query = case["turns"][0]["user_query"]
        gold = set(case["retrieval_ground_truth"]["gold_chunk_ids"])
        began = time.perf_counter()
        query_vec = json.dumps(embed(["为这个句子生成表示以用于检索相关文章：" + query])[0])
        vector = [row[0] for row in db.execute(
            "SELECT id FROM t_knowledge_vector WHERE collection_name=%s "
            "ORDER BY embedding <=> %s::vector, id LIMIT %s", (COLLECTION, query_vec, POOL))]
        vec_ms = milliseconds(began)
        began = time.perf_counter()
        lexical = [row[0] for row in db.execute(keyword, (query, COLLECTION, POOL))]
        rank_score = {}
        for channel in (vector, lexical):
            for rank, identifier in enumerate(channel, 1):
                rank_score[identifier] = rank_score.get(identifier, 0) + 1 / (RRF_K + rank)
        hybrid = sorted(rank_score, key=lambda key: (-rank_score[key], key))[:POOL]
        hybrid_ms = vec_ms + milliseconds(began)
        assert len(vector) == len(hybrid) == POOL
        combinations = ((vector, vec_ms), (vector, vec_ms), (hybrid, hybrid_ms))
        for variant, (pool, retrieval_ms) in zip(VARIANTS, combinations, strict=True):
            began = time.perf_counter()
            ordered = pool if variant == "vec_only" else rerank(query, pool, chunks)
            rank_ms = 0 if variant == "vec_only" else milliseconds(began)
            top = ordered[:FINAL]
            first = next((index for index, key in enumerate(top, 1) if key in gold), 0)
            observations.append({
                "case_id": case["case_id"], "category": case["category"], "config_group": variant,
                "candidate_count": len(pool), "reranker_inputs": 0 if variant == "vec_only" else len(pool),
                "candidate_ids": "|".join(pool), "top5_chunk_ids": "|".join(top),
                "first_gold_rank": first, "hit_at_5": int(first > 0),
                "recall_at_5": len(gold.intersection(top)) / len(gold),
                "rr_at_5": 1 / first if first else 0, "retrieval_ms": retrieval_ms, "rerank_ms": rank_ms,
            })
        print(case["case_id"], flush=True)
    return observations


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", required=True, type=Path)
    parser.add_argument("--out", type=Path, default=DESTINATION)
    parser.add_argument("--port", type=int, default=29432)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--sql-only", action="store_true")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((DESTINATION / "corpus_manifest.json").read_text(encoding="utf-8"))
    if sha(CATALOGUE) != manifest["snapshot_sha256"]:
        raise RuntimeError("Catalog file drifted from the frozen benchmark")
    keyword = KEYWORD_SQL.read_text(encoding="utf-8").replace(
        "/* scope */ TRUE", "v.collection_name = :collection"
    ).replace("%>", "%%>").replace(":query", "%s").replace(":collection", "%s").replace(":limit", "%s")
    with psycopg.connect(host="127.0.0.1", port=args.port, dbname="store_eval", user="postgres",
                         autocommit=True, connect_timeout=5) as db:
        db.execute("CREATE EXTENSION IF NOT EXISTS vector")
        db.execute("CREATE TABLE IF NOT EXISTS t_knowledge_vector("
                   "id text PRIMARY KEY, collection_name text NOT NULL, content text, embedding vector(512))")
        migration = MIGRATION.read_text(encoding="utf-8")
        db.execute(migration)
        db.execute(migration)
        check_lexical(db, keyword)
        check_existing_rows_migrate(db, migration, keyword)
        if args.sql_only:
            return
        data = json.loads((DESTINATION / "eval_dataset_50.json").read_text(encoding="utf-8"))
        kept = [case for case in data if case["in_rag_ablation"]]
        models = get_models(args.models, args.threads)
        observations = evaluate(db, kept, *models, keyword)
    path = args.out / "rag_ablation_results.csv"
    with path.open("w", encoding="utf-8-sig", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=observations[0])
        writer.writeheader()
        writer.writerows(observations)
    scores = {}
    for variant in VARIANTS:
        rows = [row for row in observations if row["config_group"] == variant]
        scores[variant] = {metric: mean(row[metric] for row in rows)
                           for metric in ("hit_at_5", "recall_at_5", "rr_at_5")}
    base, hybrid = (scores[variant] for variant in VARIANTS[1:])
    gain = hybrid["recall_at_5"] - base["recall_at_5"]
    reduction = gain / (1 - base["recall_at_5"]) if base["recall_at_5"] < 1 else None
    summary = {"cases": len(kept), "chunks": len(manifest["chunks"]), "scores": scores,
               "recall_gain": gain, "relative_miss_reduction": reduction,
               "offline_gate_passed": ((gain >= 0.05 or (reduction or 0) >= 0.25)
                                       and hybrid["rr_at_5"] >= base["rr_at_5"]),
               "snapshot": manifest["snapshot_sha256"], "model_hashes": {
                   model: sha(args.models / model / "model.safetensors")
                   for model in (VEC_MODEL, RERANK_MODEL)}}
    (args.out / "rag_ablation_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
