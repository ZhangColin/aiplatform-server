FROM node:22-bookworm

# 开发智能体（OpenCode）装进项目环境镜像 —— 决策票 05。
# 注意：适配层不锁死 OpenCode（ADR-0001），换 DSH/其他 = 换镜像内容 + 换 CodingAgentAdapter 实现。
RUN npm install -g opencode-ai --no-audit --no-fund

# 预装自定义 provider 依赖：opencode 首次使用自定义 provider 时会现场 npm 安装
# @ai-sdk/openai-compatible（同步阻塞、可能数分钟——新容器第一条消息卡死的元凶）。
# 预装进镜像后首条消息即秒回。
RUN npm install -g @ai-sdk/openai-compatible --no-audit --no-fund

# 第二个开发智能体（DeepSeek Harness / DSH）—— ADR-0004。
# 后端适配层（DshAdapter）在容器内执行 `dsh --profile headless "<任务>"`：
# 一次性任务模式（headless 组合包，内置 profile），打印最终回复后退出——
# 无监听端口、无交互提问 surface。模型/推理档位经 $DSH_HOME/settings.yaml 注入，
# API Key 走容器环境变量 DEEPSEEK_API_KEY（凭据解析最高优先级）。
RUN npm install -g @deepseek-ai/dsh --no-audit --no-fund

# 极简静态文件服务器：demo 预览示意（环境抽象 exposePort 能力）
COPY serve.js /opt/serve.js
