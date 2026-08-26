package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentWorkspace;
import com.aieducenter.aiplatform.base.chatagent.domain.port.PrdArtifactPort;

/**
 * 保存 PRD 工具（#49）：BA 判定需求明确（含催促收敛）后的产物动作——效果 =
 * 写 {@code docs/PRD.md} 到项目 dev 工作区（经 {@link PrdArtifactPort#workspacePath}
 * 的业务正本路径，docker exec 覆盖写——修订再执行即更新）+ 回调
 * {@link PrdArtifactPort#onWritten}（置「PRD 已产出」状态位 + 发 document-updated，
 * 归 business.project）。落盘/回调任一失败回错误结果（模型可见，可再次调用重试）。
 *
 * <p>无需用户确认（权限自检恒放行）：PRD 产出是访谈协议的预期终点，用户的确认
 * 动作是之后的 G1 门拍板，不在工具调用点。仅随项目 dev 工作区注册（工厂分型，
 * {@link AgentscopeHarnessAgentFactory#interviewToolkit}）——本地兜底工作区无
 * 项目语境，不注册即模型不可见。执行体阻塞（docker exec + 置位事务），框架
 * ToolExecutor 缺省 boundedElastic 调度，阻塞安全。</p>
 */
public class SavePrdTool extends ToolBase {

    public static final String NAME = "savePrd";
    private static final String CONTENT_KEY = "content";

    private final String workspaceId;
    private final PrdArtifactPort artifactPort;
    private final String containerFile;
    private final DockerExecFilesystem.ExecCommand exec;

    public SavePrdTool(String artifactPath, String workspaceId, String containerName,
            PrdArtifactPort artifactPort) {
        this(artifactPath, workspaceId, artifactPort, new DockerExecFilesystem.DockerExecCommand(
                containerName, ChatAgentWorkspace.ProjectDev.CONTAINER_ROOT));
    }

    /** 执行缝注入构造（单测替身 ExecCommand）。 */
    SavePrdTool(String artifactPath, String workspaceId, PrdArtifactPort artifactPort,
            DockerExecFilesystem.ExecCommand exec) {
        super(ToolBase.builder()
                .name(NAME)
                .description("保存/修订 PRD 到项目工作区（docs/PRD.md）。判定需求已明确"
                        + "（四方面信息齐备或用户催促收敛）时调用：content 传完整 PRD "
                        + "markdown 全文（含需求背景、目标用户、核心场景、范围边界、"
                        + "关键约束、待定项）。每次调用覆盖旧版；成功后向用户简短总结，"
                        + "不再提问。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                CONTENT_KEY, Map.of(
                                        "type", "string",
                                        "description", "完整 PRD markdown 全文（覆盖旧版）")),
                        "required", List.of(CONTENT_KEY)))
                .readOnly(false)
                .concurrencySafe(false));
        this.workspaceId = workspaceId;
        this.artifactPort = artifactPort;
        this.containerFile = ChatAgentWorkspace.ProjectDev.CONTAINER_ROOT + "/" + artifactPath;
        this.exec = exec;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 产出是访谈的预期终点（确认动作在之后的 G1 门），工具点不放确认
        return Mono.just(PermissionDecision.allow("PRD 产出是访谈的预期终点，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Object content = param.getInput() != null ? param.getInput().get(CONTENT_KEY) : null;
        if (content == null || String.valueOf(content).isBlank()) {
            return Mono.just(ToolResultBlock.error("content 不能为空：传完整 PRD markdown 全文"));
        }
        // 覆盖写（文件面写路径单一缝，修订即更新）
        DockerExecFilesystem.ExecOutput out = exec.run(
                DockerExecFilesystem.overwriteWriteCommand(containerFile),
                String.valueOf(content).getBytes(StandardCharsets.UTF_8));
        if (!out.ok()) {
            return Mono.just(ToolResultBlock.error("PRD 写入工作区失败: " + out.stderr()));
        }
        try {
            artifactPort.onWritten(workspaceId);
        }
        catch (RuntimeException e) {
            return Mono.just(ToolResultBlock.error("PRD 已写入但业务登记失败，可重试保存: "
                    + e.getMessage()));
        }
        return Mono.just(ToolResultBlock.text("PRD 已保存到项目工作区 " + containerFile));
    }
}
