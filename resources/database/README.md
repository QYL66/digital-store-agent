# 数码商城智能体数据库

## 本机初始化

[中间件启动脚本](../../local/Start-Middleware.ps1) 在 `store_agent` 数据库执行初始化和商城导入。

| 文件 | 用途 |
| --- | --- |
| [`schema_pg.sql`](schema_pg.sql) | 初始化 PostgreSQL 表结构及 pgvector 扩展 |
| [`init_data_pg.sql`](init_data_pg.sql) | 写入内置角色和基础配置 |
| [`store-agent.sql`](../../mcp-server/src/main/resources/db/store-agent.sql) | 初始化商城目录、演示网点和预约表 |
| [`store_agent_seed.sql`](store_agent_seed.sql) | 注册商城工具、意图、技能和示例问题 |
| [`memory-version.sql`](upgrades/store-agent/memory-version.sql) | 补齐长期记忆的版本和处理水位字段 |

公开目录通过 [商品目录导入脚本](../../local/Import-StoreCatalog.ps1) 导入。已部署的商城数据库可由同一启动脚本应用商城脚本和记忆字段升级。

## 历史迁移

`upgrades/v1.1.0/` 和 `upgrades/v2.0.0/` 保留通用模块的历史迁移。新建商城数据库按上面的全量初始化流程，旧库迁移按来源版本和文件日期依次应用对应脚本。
