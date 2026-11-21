# 商城问答评测

[问题集](questions.json)包含 60 条带预期结果的用例，覆盖商品参数、规格比对、兼容证据、促销规则、售后和证据不足。用 [目录导入脚本](../../local/Import-StoreAgentDemo.ps1)将同一份公开资料摘要入库。首次建立评测知识库时用 Hybrid 模式索引，以便生成向量、关键词与图谱三种索引。

复现 2026-11-20 的评测时，在新环境中首次导入的目录用 `-Snapshot .\docs\evaluation\catalog-snapshot-2026-11-20.json` 指定，共生成 52 份摘要。[归档目录快照](catalog-snapshot-2026-11-20.json)

## 录制和评分

用三个检索模式依次启动后端，每次运行对应评分命令，保持目录、模型、评测题及最终上下文预算一致。切换模式前用停止脚本结束应用，数据库和索引保留。RAGAS 和评委模型配置通过本机虚拟环境及环境变量提供。需要一个兼容 OpenAI 接口的评委模型和一个向量模型。

| 变量 | 用途 |
| --- | --- |
| `STORE_EVAL_JUDGE_BASE`、`STORE_EVAL_JUDGE_KEY`、`STORE_EVAL_JUDGE_MODEL` | 评委模型地址、密钥和名称 |
| `STORE_EVAL_EMBEDDING_BASE`、`STORE_EVAL_EMBEDDING_KEY`、`STORE_EVAL_EMBEDDING_MODEL` | 相关性评测使用的向量模型 |

```powershell
python -m venv .venv
& .\.venv\Scripts\python.exe -m pip install -r docs/evaluation/requirements.txt
& .\.venv\Scripts\python.exe docs/evaluation/run.py --mode vector
# 切到 Keyword 后录制第二组
& .\.venv\Scripts\python.exe docs/evaluation/run.py --mode keyword
# 切到 Hybrid 后录制第三组
& .\.venv\Scripts\python.exe docs/evaluation/run.py --mode hybrid
& .\.venv\Scripts\python.exe docs/evaluation/run.py --compare
```

管理员评测流提供当前问答实际使用的上下文和检索排名。记录保留在 `local/logs/evaluation/`。报告包含整体与各业务类别的汇总，也保留逐题原始分、不适用项、评分异常和修改记录。人工校正可通过 `--corrections` 传入 JSON 数组，条目包含 `mode`、`id`、`metric`、`score` 和 `reason`。使用 `--compare` 可重汇总已有结果。

问答并发默认是 1。可用 `--concurrency` 指定 1–4，三种模式应使用相同并发。该值进入对比元数据，便于复现实验。

某次评测列出评分错误时，可用相同配置加 `--resume` 复跑。已取得问答证据的题只重算失败的指标，请求失败的题会重新采集 SSE。

本机 2026-11-20 完成三套各 60 题。每套并发 2，三组在同一主机上运行。首轮个别评分返回结构化输出截断，续跑保留原回答和上下文，提高评委输出上限后重算。每套最终有 60 个 Faithfulness 和 60 个回答相关性分数。公开报告保存 24 项指标、场景分层和 120 条逐题差值，本次未修改原始评分。[本次汇总](store-results-2026-11-20.json)

## 24 项指标

- **RAGAS**。Faithfulness、Response Relevancy、Context Precision、Context Recall、Context Entity Recall、Factual Correctness。
- **检索排名**。Hit@1、Hit@5、Hit@10、MRR@10、nDCG@10、Recall@10。排名按资料稳定编号去重。
- **业务答复**。答案标记覆盖、全部标记命中、引用精度、引用召回、无依据时的说明、歧义澄清。自动标记是核查线索，错误结论由人工校正。
- **性能**。首字耗时 P50/P95、总耗时 P50/P95、平均检索耗时、请求错误率。

图谱、Rerank 及评委模型的可用性要在报告中核实。预约并发及确认效果使用 [MCP 检查](../../local/Verify-StoreAgentMcp.ps1)与浏览器验收记录。
