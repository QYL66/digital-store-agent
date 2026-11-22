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

### 云模型端到端核验

50 题共 60 个回合，全部收到完整的 SSE 回答。另有 3 次请求用于预热和定位采集脚本的结束帧解析问题，未计入 60 回合的分数与耗时。采集脚本已兼容项目发送的 `[DONE]` 结束标记。

| 指标 | 实测 |
| --- | ---: |
| 预标注关键事实通过 | 44/50 |
| 提供全部预标注来源的可点击 URL | 36/50 |
| 预期 MCP 工具完成匹配 | 45/60 |
| 首轮追问正确缺失字段 | 4/10 |
| 快充风险案例越界使用功率证据 | 1/22 |

工具匹配按预先标注的工具名与成功状态评分。预期直接澄清的回合如出现工具调用，该回合的工具匹配记为未通过。事实评分对照指定关键事实和禁止断言。引用必须覆盖全部预标注 URL，只给出处名称或通用商城链接记为未通过。快充按案例计数，任一回合把标称值或系列区间作为具体机型配对功率即记 1 次。本次唯一越界来自 CL-02，将系列功率区间套到了具体小米 17 Pro 的配对场景。同一账号运行中，部分回答引用了账号中的手机型号记忆，澄清分数包含这种影响。

| 该回合观察到的工具调用次数 | 回合数 | SSE 首事件 P50 | 首条进展 P50 | 首段可见正文 P50 | 首段正文中位 50% 区间 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0–1 次 | 15 | 21.2 ms | 438.1 ms | 1.70 s | 1.63–2.07 s |
| ≥2 次 | 45 | 21.3 ms | 456.7 ms | 5.57 s | 4.57–6.89 s |

首事件含 `meta`。首段可见正文从客户端发起本机 SSE 请求起计时，包含完整的应用编排、模型调用和工具往返。独立取 SSE 标记为工具体耗时的本机商城 MCP 调用 164 次，P50 为 5 ms，中位 50% 区间为 5–6 ms。在前述 **离线** PG 混合组内，含本地向量化的检索 P50 为 20.86 ms，CPU 重排 P50 为 889.85 ms。这两项使用冻结的 30 题离线数据，和端到端云模型样本的计时独立。

[逐回合评测表](benchmark/e2e_eval_results.csv) · [端到端汇总数据](benchmark/e2e_summary.json)
