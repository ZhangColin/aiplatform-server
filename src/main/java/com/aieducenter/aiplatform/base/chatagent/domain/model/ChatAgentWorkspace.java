package com.aieducenter.aiplatform.base.chatagent.domain.model;

import java.nio.file.Path;

/**
 * 对话智能体的工作区锚定（#45，ADR-0002 概念对口「Workspace/Sandbox ↔ dev 工作区」）：
 * HarnessAgent 的文件面落点，两形态——
 *
 * <ul>
 *   <li>{@link Local}：平台本地目录（#44 既有口径，配置兜底）——HarnessAgent 默认
 *       本地文件系统直用</li>
 *   <li>{@link ProjectDev}：项目 dev 工作区——dev 容器内 {@code /workspace}（Docker
 *       常开口径），对话智能体经 docker exec 读写，写入即落项目工作区（源码包可见，
 *       与编码引擎同视图）</li>
 * </ul>
 */
public sealed interface ChatAgentWorkspace {

    /** 工作区身份键（工厂复用缓存键的组成部分）。 */
    String identity();

    /** 平台本地工作区：{@code root} 为空表示用 AgentScope 默认。 */
    record Local(Path root) implements ChatAgentWorkspace {

        @Override
        public String identity() {
            return "local:" + (root != null ? root.toString() : "<default>");
        }
    }

    /**
     * 项目 dev 工作区：{@code containerName} 为 dev 容器名（工作区句柄解析所得），
     * 容器内工作区根恒 {@code /workspace}（与供给/打包口径同源）。
     */
    record ProjectDev(String workspaceId, String containerName) implements ChatAgentWorkspace {

        public ProjectDev {
            if (workspaceId == null || workspaceId.isBlank()
                    || containerName == null || containerName.isBlank()) {
                throw new IllegalArgumentException("ProjectDev 工作区需要 workspaceId 与 containerName");
            }
        }

        /** 容器内工作区根（dev 供给口径：-v vol:/workspace -w /workspace）。 */
        public static final String CONTAINER_ROOT = "/workspace";

        @Override
        public String identity() {
            return "project-dev:" + containerName;
        }
    }
}
