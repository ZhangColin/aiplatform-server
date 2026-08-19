# aiplatform-server

AI 开发平台后端（Phase A）：`base` 底座（环境/智能体适配/事件/知识/流程引擎）+ `business` 业务层双分区，单 module，基于 cartisan-boot 框架。领域词汇以 [CONTEXT.md](CONTEXT.md) 为正本。

## 核心文档

- [CONTEXT.md](CONTEXT.md) — 领域词汇表（写代码/文档/讨论的统一语言）
- [B0 · 底座重写切片蓝图](docs/spec/B0-底座切片蓝图.md) — 包结构定稿、六片切片与验收口径
- [SSE 事件清单](docs/spec/SSE事件清单.md) — SSE 双通道信封与事件名册（对接正本，[ADR-0001](docs/adr/0001-swagger-contract-and-sse-channels.md) 定稿：swagger 唯一契约、API 约定、鉴权 BFF 形态）
- [cartisan-boot 使用手册](docs/guide/cartisan-boot-使用手册.md) — 框架能力清单、API 文档和使用示例
- [限界上下文代码编写规范](docs/guide/限界上下文代码编写规范.md) — DDD 六边形架构落地指南

## 引用的 cartisan-boot 模块

- `cartisan-core` — DDD 基础类型、异常体系、架构注解、RequestContext
- `cartisan-web` — 统一响应体、全局异常处理、请求上下文、防重提交
- `cartisan-data-jpa` — BaseRepository、事件发布、审计、软删除
- `cartisan-openapi` — 服务间签名验证、API Key 管理
- `cartisan-test` — ArchUnit 规则、测试基类

不引入 `cartisan-security`：认证走 identity 服务 OIDC + BFF（照 `aieducenter-identity/demo`），见 [ADR-0001](docs/adr/0001-swagger-contract-and-sse-channels.md)。

## 常用命令

- 编译：`mvn compile`
- 单元测试：`mvn test`
- 打包：`mvn package -DskipTests`
- 变异测试：`mvn org.pitest:pitest-maven:mutationCoverage`

## Agent skills

### Issue tracker

Issue 通过 GitHub Issues 管理（`gh` CLI）。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认五角色标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。见 `docs/agents/triage-labels.md`。

### Domain docs

Single-context 布局：仓库根目录 `CONTEXT.md` + `docs/adr/`（由 `/domain-modeling` 惰性创建）。见 `docs/agents/domain.md`。
