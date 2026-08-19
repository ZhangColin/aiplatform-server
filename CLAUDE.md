# aiplatform-server

Gateway 网关服务，基于 cartisan-boot 框架。

## 核心文档

- [cartisan-boot 使用手册](docs/guide/cartisan-boot-使用手册.md) — 框架能力清单、API 文档和使用示例
- [限界上下文代码编写规范](docs/guide/限界上下文代码编写规范.md) — DDD 六边形架构落地指南

## 引用的 cartisan-boot 模块

- `cartisan-core` — DDD 基础类型、异常体系、架构注解、RequestContext
- `cartisan-web` — 统一响应体、全局异常处理、请求上下文、防重提交
- `cartisan-data-jpa` — BaseRepository、事件发布、审计、软删除
- `cartisan-openapi` — 服务间签名验证、API Key 管理
- `cartisan-security` — Sa-Token 认证集成、权限注解
- `cartisan-test` — ArchUnit 规则、测试基类

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
