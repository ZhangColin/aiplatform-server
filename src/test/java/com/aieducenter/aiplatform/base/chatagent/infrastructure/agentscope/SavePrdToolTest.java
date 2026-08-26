package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.aieducenter.aiplatform.base.chatagent.domain.port.PrdArtifactPort;

/**
 * {@link SavePrdTool}（#49）：覆盖写落盘（业务正本路径）+ 落盘成功回调端口 +
 * 失败回错误结果（模型可见可重试）；权限自检恒放行（确认动作在 G1 门，不在工具点）。
 */
class SavePrdToolTest {

    /** 执行缝替身：记录命令与 stdin，返回预设结果。 */
    private static final class RecordingExec implements DockerExecFilesystem.ExecCommand {
        final List<String> commands = new ArrayList<>();
        final List<byte[]> stdins = new ArrayList<>();
        DockerExecFilesystem.ExecOutput next =
                new DockerExecFilesystem.ExecOutput(0, new byte[0], "");

        @Override
        public DockerExecFilesystem.ExecOutput run(String command, byte[] stdin) {
            commands.add(command);
            stdins.add(stdin);
            return next;
        }
    }

    /** 端口替身：记录回调，可预设抛出。 */
    private static final class RecordingPort implements PrdArtifactPort {
        final List<String> callbacks = new ArrayList<>();
        RuntimeException failure;

        @Override
        public String workspacePath() {
            return "docs/PRD.md";
        }

        @Override
        public void onWritten(String workspaceId) {
            if (failure != null) {
                throw failure;
            }
            callbacks.add(workspaceId);
        }
    }

    private final RecordingExec exec = new RecordingExec();
    private final RecordingPort port = new RecordingPort();
    private final SavePrdTool tool = new SavePrdTool("docs/PRD.md", "42", port, exec);

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("savePrd");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        assertThat(String.valueOf(tool.getParameters().get("required"))).contains("content");
        assertThat(tool.isReadOnly()).isFalse(); // 写工作区产物
    }

    @Test
    void given_any_call_when_check_permissions_then_always_allow() {
        // PRD 产出是访谈协议的预期终点：工具点不放确认（用户的确认在 G1 门拍板）
        PermissionDecision decision = tool
                .checkPermissions(Map.of("content", "# PRD"), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void given_content_when_called_then_written_and_port_called() {
        ToolResultBlock result = call("# PRD\n\n做一个企业官网。");

        // text 工厂缺省 RUNNING（同 ask_user 形制），非 ERROR 即成功载体
        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("已保存");
        // 覆盖写（cat >，无 noclobber——修订即更新）+ mkdir 兜底目录；内容经 stdin
        assertThat(exec.commands).containsExactly(
                "mkdir -p '/workspace/docs' && cat > '/workspace/docs/PRD.md'");
        assertThat(new String(exec.stdins.get(0), StandardCharsets.UTF_8))
                .isEqualTo("# PRD\n\n做一个企业官网。");
        assertThat(port.callbacks).containsExactly("42");
    }

    @Test
    void given_second_call_when_revision_then_overwrite_and_callback_again() {
        call("# PRD v1");
        call("# PRD v2（修订）");

        // 修订再执行：文件重写 + 状态位/事件再触发（三更新）
        assertThat(exec.commands).hasSize(2);
        assertThat(port.callbacks).containsExactly("42", "42");
    }

    @Test
    void given_blank_content_when_called_then_error_without_write() {
        ToolResultBlock result = call(" ");

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(exec.commands).isEmpty();
        assertThat(port.callbacks).isEmpty();
    }

    @Test
    void given_exec_failure_when_called_then_error_and_no_port_callback() {
        exec.next = new DockerExecFilesystem.ExecOutput(125, new byte[0],
                "docker: No such container");

        ToolResultBlock result = call("# PRD");

        // 落盘失败：文件未成——不置位不发事件（门禁事实只认落盘成功）
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("写入工作区失败");
        assertThat(port.callbacks).isEmpty();
    }

    @Test
    void given_port_failure_when_called_then_error_retriable() {
        port.failure = new RuntimeException("置位失败");

        ToolResultBlock result = call("# PRD");

        // 文件已写但业务登记失败：回错误让模型可重试（写幂等覆盖，重试只前进）
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("登记失败");
    }

    // ---------- 内部 ----------

    private ToolResultBlock call(String content) {
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock(
                        "tc-1", SavePrdTool.NAME, Map.of("content", content), null))
                .input(Map.of("content", content))
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
