# 商城查询评测

公开商城目录、提问与人工标注被冻结为一份 50 题数据集，其中 30 个单轮问题进入检索消融。规格、对比、兼容和售后内容来自仓库内公开资料快照。两轮题的首轮要求补全缺失字段。

## 本地检索

在隔离的 PostgreSQL/pgvector 中加载固定语料，每条目录记录对应一个分块。用 BGE Small 中文模型生成向量，BGE Base 重排模型处理查询与候选对。对照 `vec_only`、`vec_rerank` 和 `pg_hybrid_rrf_rerank`。后两组每题向同一模型提交 20 个去重候选，输出 Top 5。混合组复用项目的 PostgreSQL 关键词 SQL，按等权 RRF（`k=20`）合并向量与关键词名次。评测数据库使用本地模型的 512 维向量列，应用现有云向量使用其独立的 1536 维列。

- [50 题题库与标注](benchmark/eval_dataset_50.json)
- [公开快照与分块清单](benchmark/corpus_manifest.json)
- [本地检索跑测程序](benchmark/run_retrieval.py)

Hit@5 统计是否找回任一正确证据，Recall@5 为找回的正确证据占该题全部正确证据的比例，MRR@5 计算首个正确证据的名次倒数。报告对各题等权取均值。检索消融的表现适用于这次冻结语料、问题和本地模型。

表中的检索耗时包含本地查询向量化和数据库检索，混合组加上关键词与融合的顺序计时。重排耗时单列，应用端的并行调度和模型耗时以实际链路观测为准。

## 真实对话

逐回合记录本地 SSE 的首事件、首条进展与首段可见正文。工具体耗时取 SSE 的 `durationMs`，同时保留 `durationSource`。首事件可以是 `meta`，进展计时单独报告。用首轮澄清、工具调用、事实、出处和快充标称功率误报作人工核对。未完成或未评分的观测保留状态与分母。

## 复现

用 Python 3.13、CPU PyTorch 2.8.0、Transformers 4.56.2、psycopg 3.2.12、PostgreSQL 16 与 pgvector。只在专用评测数据库运行下列命令。模型目录包含 BAAI 的 `bge-small-zh-v1.5`（修订 `7999e1d`）和 `bge-reranker-base`（修订 `2cfc18c`），运行过程使用本地权重。

```powershell
docker run -d --name store-benchmark -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=store_eval -p 127.0.0.1:29432:5432 pgvector/pgvector:pg16
docker exec store-benchmark pg_isready -U postgres -d store_eval
python docs/benchmark/build_dataset.py
python docs/benchmark/run_retrieval.py --models D:/path/to/models --out D:/path/to/results
docker stop store-benchmark
```

端到端脚本使用已启动的本机商城和本机登录口令。原始回答只写入本地结果目录，逐题核对后再发布评分表。

```powershell
python docs/benchmark/run_e2e.py --out D:/path/to/e2e-results
```

<!-- verified-results -->
## 实测结果

本机离线检索完成 30 题、121 个冻结语料分块。

| 检索配置 | Hit@5 | Recall@5 | MRR@5 |
| --- | ---: | ---: | ---: |
| 纯向量 | 56.7% | 48.3% | 0.448 |
| 向量与重排 | 80.0% | 70.0% | 0.767 |
| PostgreSQL 混合检索与重排 | 93.3% | 86.7% | 0.883 |

[逐题检索结果](benchmark/rag_ablation_results.csv) · [模型及语料校验摘要](benchmark/rag_ablation_summary.json)

PG 混合组较同样使用重排的向量组，Recall@5 提升 16.7 个百分点，漏召回率相对下降 55.6%，MRR@5 也上升。旧表升级及重复执行的验证使用已有行和 1536 维定义。Java 集成测试在无 ES 的 Spring 上下文内，使用预计算向量及真实 PostgreSQL 验证 pgvector、关键词、分块去重和 RRF。与配置、关键词通道和精排回归合计 24 项通过。本机启动器默认启用 PG 混合检索，ES 为显式可选，`-RetrievalMode Vector` 可回退。

[端到端核验表](benchmark/e2e_eval_results.csv)预登记 60 个回合，本次均尚未运行。实际云模型会话可用后再核验端到端耗时、事实、引用与快充误报。
