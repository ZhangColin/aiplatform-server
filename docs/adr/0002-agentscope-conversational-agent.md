# 对话智能体内核选型 AgentScope Java，与编码引擎双轨分野

> 状态：已接受（2026-08-25 · [#40 grilling](https://github.com/ZhangColin/aiplatform-server/issues/40) 落定，基座票 [#44](https://github.com/ZhangColin/aiplatform-server/issues/44)/[#45](https://github.com/ZhangColin/aiplatform-server/issues/45)）

BA 访谈（多轮提问 → 判定明确 → 产出 PRD）这类**对话型智能体**不跑在编码引擎（OpenCode/dsh）上；平台智能体内核选 [AgentScope Java 2.0](https://github.com/agentscope-ai/agentscope-java)。本 ADR 记选型理由、双轨分野与 PRD 落位三项决策，实施细节以各票 brief 为正本。

## 决策

### 智能体内核：AgentScope Java 2.0，不自研薄胶水

- 事实：v2.0.1 GA（2026-07），JDK 17+，Maven Central `io.agentscope:agentscope-harness`；模型扩展模块含 deepseek / gemini / openai / anthropic / dashscope / ollama（模型串如 `deepseek:...` 自动解析并读对应 API key 环境变量）。
- 选型依据（grilling 定案）：平台终局是多种智能体 + harness / skill / MCP 生态——**智能体不应只是 LLM + Prompt**。薄胶水 v1 够用，但一定演化成重造 harness（工具循环、分层记忆、skill 沉淀、HITL、沙箱）；自研的废弃成本大于框架的学习成本。
- 概念对口（非拼凑，是补上平台缺的内核）：

| 平台已有 | AgentScope 提供 | 映射 |
|---|---|---|
| QUESTION/PERMISSION 等待点（settle 即续跑） | HITL 三态 + 暂停恢复 | 双向桥（#45） |
| dev 工作区（Docker 常开） | Workspace/Sandbox | BA 的 workspace 指向项目工作区 |
| agent 流通道（ADR-0001） | 28 类型化事件流 | 事件 → 流帧桥（兑现 ADR-0001「AgentScope event 同构」预留） |
| UsageEvent（OpenAI/DeepSeek 解析已有） | 模型调用事件 | 计量埋点（#44） |
| （缺）智能体内核 | HarnessAgent：ReAct + skill 自动沉淀 + 分层记忆 + Plan Mode | 本决策引入 |

### 双轨分野：对话智能体（平台级）vs 编码引擎（项目级）

- `Project.engine` 只指**编码引擎**（opencode/dsh——写代码的，DEV/TEST/DEMO/ARCH/修复）；引擎全局配置端点（#42）管的是它。
- BA 等对话角色走 **AgentScope HarnessAgent**（平台进程内），base 层新模块与 base.agentengine 分立起步，不搅动现有引擎适配；编码引擎路径行为零变化（#45 验收含此条）。
- 基座拆两票：#44 引入 + 最小骨架（依赖、工厂、计量、冒烟）；#45 平台接线（事件→SSE、HITL→等待点、workspace=项目工作区、会话恢复落 PostgreSQL）。

### PRD = 工作区事实源

- PRD 是项目 dev 工作区的 `docs/PRD.md`——**工作区的一部分**：编码智能体（DEV/ARCH）写代码时自主可读，零平台注入搬运；BA 经 `savePrd` 工具写入（效果 = 写文件 + 置「PRD 已产出」状态位 + 发 `document-updated`）。
- 读端点（#41）**直读工作区**（先例：`source-package` 即从工作区打包，容器常开口径），**不建新表**；无版本链 v1 不做，将来要版本链再加库投影。
- G1 门就绪 = 业务谓词「PRD 已产出」（查状态位，不查文件系统；照 G3 无未关闭 Bug 先例挂门编排层）。A3 主链规格的 G1 门语义随 #40 实施同步修订。

### 取名异步（行业惯例）

- 项目名由 LLM 生成（ChatGPT/Claude/DeepSeek/Gemini 同款模式）：创建响应即时返回占位「未命名项目」，后台 AgentScope 轻调用取名，完成静默换名——**不新增 SSE 事件**，前端常规 invalidate 拉详情见到新名（#39）。
- 红线：禁止字符串截取派生（前端过渡逻辑删除，后端自始无截取兜底）。

## Considered Options

- **BA 继续跑编码引擎（改造现状）**：否——对话智能体不需要工作区工具/终端；黑盒引擎无结构化产出，PRD 检出被逼成「run 终态读工作区文件」的绕路；PRD 也进不了对话上下文。用挖掘机浇花。
- **自研薄 LLM client（Spring AI / 直连 OpenAI 兼容口）**：否——v1 可行，但见选型依据：薄胶水的演化终点是重造 harness，中途废弃成本更高。
- **AgentScope Python 版**：否——第二运行时、独立部署面、双栈维护。
- **Spring AI Alibaba**：否——其定位是 Spring 生态接入层/桥，内核正是 AgentScope（官方口径：Spring AI 在推进内核升级为 AgentScope）；直接用正主，少一层间接。

## Consequences

- 新依赖与学习成本即刻发生；#40（BA 访谈循环）依赖链 = #44 → #45 → #40 → #46（G2 Demo 回流）。
- 平台获得多智能体扩展位：子智能体编排（`agent_spawn`/`agent_send`）、skill 自动沉淀、MCP 生态将来按需启用；AgentScope Service（控制面/托管平台）为远期可选项，Phase A 不引入。
- B0 蓝图包结构随 #44 实施登记新 base 分区；SSE 事件清单登记 `document-updated` 归 #41。
- 对话智能体的计量归属（subject/dims）随 #44 参数化落地，口径对齐 A6 计量规格。
