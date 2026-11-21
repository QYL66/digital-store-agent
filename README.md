# 数码商城智能体

数码商城智能体是一个面向小米商城中国大陆站的查询助手。它沿用 Ragent 的 Agent、RAG 和管理后台，接入 Xiaomi、REDMI 手机与充电配件的公开目录，也可以查询优惠券、价保和售后资料。

目录快照包含 **44 条手机记录、71 条充电配件记录、4 条政策和 2 条故障资料**。查询结果带有官方来源和资料核对日期。实时上新、库存、价格、个人优惠和真实售后办理以小米官网为准。[公开资料来源](resources/store-catalog/sources.json) · [目录分页记录](resources/store-catalog/listing-scan.json) · [小米商城](https://www.mi.com/shop)

## 查询入口

| 场景 | 示例 |
| --- | --- |
| 商品咨询 | REDMI K90 的电池容量和有线充电功率是多少？ |
| 规格比对 | 比较 REDMI K90 和 K90 Pro Max。再查 PB2165 给 K90 快充的证据。 |
| 促销政策 | 小米商城的优惠券能叠加吗？特殊活动规则在哪里看？ |
| 售后咨询 | 换了线还是充不上电，可以先排查什么？哈尔滨的授权网点在哪里查？ |

## 系统流程

聊天页面 → Ragent Agent → 商城 MCP 或 Ragent 知识库 → SSE 回答

- **商城 MCP** 在 PostgreSQL 快照中按名称、别名和系列查询。规格比对保留每款商品的出处，充电兼容性按具体机型、系列或协议证据说明。
- **Ragent 知识库** 通过管理后台导入公开资料摘要，提供文档切分、向量检索、精排和引用。管理后台也可以维护示例问题和工具配置。

## 本机启动

需要 Windows、运行中的 Docker Desktop Linux 引擎、JDK 17、Maven、Node.js 22 或更新 LTS 和 Python 3。DeepSeek 与阿里云百炼的模型 Key 在启动时于本机输入。

```powershell
mvn.cmd package
Push-Location frontend
npm.cmd ci
npm.cmd run typecheck
npm.cmd run build
Pop-Location
.\local\Start-StoreAgent.ps1
```

首次启动会初始化独立的本机数据库，导入结构化小米目录，并提示设置 `admin` 密码。打开 [数码商城智能体](http://127.0.0.1:25173/chat) 登录，管理入口位于左侧「新建会话」下方。

默认使用 pgvector，需要本机有 `pgvector/pgvector:pg16`、`redis:8.4-alpine`、`rustfs/rustfs:1.0.0-alpha.72` 和 `apache/rocketmq:5.2.0` 镜像。可按需拉取。可选的 `Keyword` 和 `Hybrid` 模式所需镜像见 [Compose 检索配置](local/compose-retrieval.yaml)。

| 操作 | 命令 |
| --- | --- |
| 重复导入结构化目录并生成覆盖报告 | `.\local\Import-StoreCatalog.ps1` |
| 导入商城摘要到 Ragent 知识库 | `.\local\Import-StoreAgentDemo.ps1` |
| 验证 MCP 只读工具与回答来源 | `.\local\Verify-StoreAgentMcp.ps1` |
| 停止应用和中间件 | `.\local\Stop-StoreAgent.ps1` |

应用前端、后端和 MCP 分别监听 `25173`、`29090` 和 `29099`。后端 API 前缀为 `/api/store-agent`。新版 Compose 项目使用 `digital-store-agent-catalog-local`，首次启动建立独立数据卷。目录快照存放在 `resources/store-catalog/records.json`，导入时可用 `-Snapshot` 指定另一个符合格式的快照。

## 源码位置

| 目录 | 作用 |
| --- | --- |
| `agent/`、`rag/` | Ragent 对话、检索、引用、会话记忆和资料入库 |
| `bootstrap/` | `StoreAgentApplication`、模型及应用配置 |
| `mcp-server/` | 只读商城目录、规格、配件、政策和售后 MCP |
| `frontend/` | 商城问答、账号与管理后台 |
| `local/`、`resources/store-catalog/` | 启停、导入与溯源数据 |

## 开源许可

本项目由 QYL66 独立维护，基于 [Ragent](https://github.com/nageoffer/ragent) 的 Apache-2.0 源码开发。[来源及改动说明](OPEN_SOURCE.md) · [Apache-2.0](LICENSE)
