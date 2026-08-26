package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope.DockerExecFilesystem.ExecCommand;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope.DockerExecFilesystem.ExecOutput;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@link DockerExecFilesystem}（#45 工作区桥，fake ExecCommand）：命令形状（根目录
 * 锚定 / 单引号转义 / noclobber create-only）、结果解析（ls/read/grep/glob）、
 * 语义对齐本地文件系统（幂等删除、遍历阻断、二进制 base64）。
 */
class DockerExecFilesystemTest {

    /** 录制式替身：按命令前缀脚本化响应。 */
    static class FakeExec implements ExecCommand {

        final List<String> commands = new ArrayList<>();
        final List<byte[]> stdins = new ArrayList<>();
        Function<String, ExecOutput> responder = c -> new ExecOutput(1, new byte[0], "no script");

        @Override
        public ExecOutput run(String command, byte[] stdin) {
            commands.add(command);
            stdins.add(stdin);
            return responder.apply(command);
        }

        boolean ran(String fragment) {
            return commands.stream().anyMatch(c -> c.contains(fragment));
        }
    }

    private final FakeExec exec = new FakeExec();
    private final DockerExecFilesystem fs = new DockerExecFilesystem(exec);

    private static ExecOutput ok(String stdout) {
        return new ExecOutput(0, stdout.getBytes(StandardCharsets.UTF_8), "");
    }

    @Test
    void given_write_when_invoked_then_noclobber_create_only_anchored_and_quoted() {
        exec.responder = c -> ok("");

        WriteResult result = fs.write(RuntimeContext.empty(), "/docs/PRD.md", "# PRD");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.path()).isEqualTo("/docs/PRD.md");
        // create-only 原子性（set -C）+ 父目录创建 + 工作区根锚定 + 单引号包裹
        assertThat(exec.ran("set -C")).isTrue();
        assertThat(exec.ran("mkdir -p '/workspace/docs'")).isTrue();
        assertThat(exec.ran("cat > '/workspace/docs/PRD.md'")).isTrue();
        assertThat(exec.stdins.get(0)).isEqualTo("# PRD".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void given_noclobber_failure_when_write_then_rejected_as_existing() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "cannot create");

        WriteResult result = fs.write(RuntimeContext.empty(), "/docs/PRD.md", "x");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("already exists");
    }

    @Test
    void given_read_text_when_invoked_then_anchored_and_sliced() {
        exec.responder = c -> ok("l1\nl2\nl3");

        ReadResult full = fs.read(RuntimeContext.empty(), "/docs/a.txt", 0, 0);
        ReadResult paged = fs.read(RuntimeContext.empty(), "/docs/a.txt", 1, 1);

        assertThat(exec.ran("test -f '/workspace/docs/a.txt' && cat '/workspace/docs/a.txt'")).isTrue();
        assertThat(full.fileData().content()).isEqualTo("l1\nl2\nl3");
        assertThat(full.fileData().encoding()).isEqualTo("utf-8");
        assertThat(paged.fileData().content()).isEqualTo("l2");
    }

    @Test
    void given_read_missing_file_when_invoked_then_not_found() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "No such file");

        ReadResult result = fs.read(RuntimeContext.empty(), "/docs/none.txt", 0, 0);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    void given_container_unreachable_when_read_then_operational_error_distinguished() {
        // docker exec 自身失败（容器不在 = 退出码 125）与文件不存在（1）必须可辨
        exec.responder = c -> new ExecOutput(125, new byte[0],
                "Error response from daemon: No such container");

        ReadResult result = fs.read(RuntimeContext.empty(), "/docs/a.txt", 0, 0);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error())
                .contains("容器不可达")
                .contains("No such container");
    }

    @Test
    void given_read_binary_extension_when_invoked_then_base64() {
        exec.responder = c -> new ExecOutput(0, new byte[]{1, 2, 3}, "");

        ReadResult result = fs.read(RuntimeContext.empty(), "/assets/logo.png", 0, 0);

        assertThat(result.fileData().encoding()).isEqualTo("base64");
        assertThat(result.fileData().content())
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
    }

    @Test
    void given_ls_when_invoked_then_entries_parsed_with_dir_slash() {
        exec.responder = c -> ok("d\t0\t1756100000.123\tdocs\n-\t12\t1756200000.456\tPRD.md\n");

        LsResult result = fs.ls(RuntimeContext.empty(), "/");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.entries()).extracting(io.agentscope.harness.agent.filesystem.model.FileInfo::path)
                .containsExactly("/PRD.md", "/docs/")   // 按路径排序（大写在前）
                .doesNotContainNull();
        var dir = result.entries().stream()
                .filter(e -> e.path().equals("/docs/")).findFirst().orElseThrow();
        var file = result.entries().stream()
                .filter(e -> e.path().equals("/PRD.md")).findFirst().orElseThrow();
        assertThat(dir.isDirectory()).isTrue();
        assertThat(file.isDirectory()).isFalse();
        assertThat(file.size()).isEqualTo(12);
    }

    @Test
    void given_ls_missing_dir_when_invoked_then_empty_success() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "No such file");

        LsResult result = fs.ls(RuntimeContext.empty(), "/none");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void given_edit_unique_match_when_invoked_then_replaced_and_written_back() {
        exec.responder = c -> c.startsWith("test -f") ? ok("你好，世界") : ok("");

        EditResult result = fs.edit(RuntimeContext.empty(), "/docs/a.txt", "世界", "AgentScope", false);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.occurrences()).isEqualTo(1);
        assertThat(new String(exec.stdins.get(exec.stdins.size() - 1), StandardCharsets.UTF_8))
                .isEqualTo("你好，AgentScope");
    }

    @Test
    void given_edit_no_match_when_invoked_then_failed() {
        exec.responder = c -> c.startsWith("test -f") ? ok("内容") : ok("");

        EditResult result = fs.edit(RuntimeContext.empty(), "/docs/a.txt", "不存在", "x", false);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    void given_edit_ambiguous_without_replace_all_when_invoked_then_failed() {
        exec.responder = c -> c.startsWith("test -f") ? ok("ab\nab") : ok("");

        EditResult result = fs.edit(RuntimeContext.empty(), "/docs/a.txt", "ab", "x", false);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("not unique");
    }

    @Test
    void given_grep_when_invoked_then_literal_search_with_input_space_paths() {
        exec.responder = c -> ok("/workspace/docs/PRD.md:3:这里有 PRD\n/workspace/README.md:7:PRD 说明");

        GrepResult result = fs.grep(RuntimeContext.empty(), "PRD", "/docs", null);

        assertThat(exec.ran("grep -rFIn")).isTrue();
        assertThat(exec.ran("-- 'PRD' '/workspace/docs'")).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches().get(0).path()).isEqualTo("/docs/PRD.md");
        assertThat(result.matches().get(0).line()).isEqualTo(3);
        assertThat(result.matches().get(0).text()).isEqualTo("这里有 PRD");
    }

    @Test
    void given_grep_no_match_when_exit_1_then_success_empty() {
        exec.responder = c -> new ExecOutput(1, new byte[0], "");

        GrepResult result = fs.grep(RuntimeContext.empty(), "PRD", "/", null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void given_glob_when_invoked_then_files_filtered_by_pattern() {
        exec.responder = c -> ok("7\t1756100000.0\t/workspace/docs/PRD.md\0"
                + "3\t1756100000.0\t/workspace/index.html\0");

        var result = fs.glob(RuntimeContext.empty(), "**/*.md", "/");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).path()).isEqualTo("/docs/PRD.md");
        assertThat(result.matches().get(0).size()).isEqualTo(7);
    }

    @Test
    void given_exists_when_exit_code_reflects_presence_then_boolean() {
        exec.responder = c -> c.endsWith("'/workspace/docs/PRD.md'") ? ok("") : new ExecOutput(1, new byte[0], "");

        assertThat(fs.exists(RuntimeContext.empty(), "/docs/PRD.md")).isTrue();
        assertThat(fs.exists(RuntimeContext.empty(), "/docs/none.md")).isFalse();
    }

    @Test
    void given_delete_when_invoked_then_recursive_rm_idempotent() {
        exec.responder = c -> ok("");

        WriteResult result = fs.delete(RuntimeContext.empty(), "/docs");

        assertThat(exec.ran("rm -rf -- '/workspace/docs'")).isTrue();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void given_move_when_invoked_then_source_checked_parent_created() {
        exec.responder = c -> ok("");

        WriteResult result = fs.move(RuntimeContext.empty(), "/a.md", "/docs/b.md");

        assertThat(exec.ran("test -e '/workspace/a.md'")).isTrue();
        assertThat(exec.ran("mkdir -p '/workspace/docs'")).isTrue();
        assertThat(exec.ran("mv -- '/workspace/a.md' '/workspace/docs/b.md'")).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.path()).isEqualTo("/docs/b.md");
    }

    @Test
    void given_upload_when_invoked_then_bytes_written_overwrite() {
        exec.responder = c -> ok("");

        var responses = fs.uploadFiles(RuntimeContext.empty(),
                List.of(Map.entry("/assets/x.bin", new byte[]{9, 9})));

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).isSuccess()).isTrue();
        assertThat(exec.ran("cat > '/workspace/assets/x.bin'")).isTrue();
        assertThat(exec.stdins.get(0)).isEqualTo(new byte[]{9, 9});
    }

    @Test
    void given_download_when_invoked_then_bytes_passthrough() {
        exec.responder = c -> c.contains("cat '/workspace/a.bin'") && c.startsWith("test -f")
                ? new ExecOutput(1, new byte[0], "No such")
                : new ExecOutput(0, new byte[]{5, 5}, "");

        var responses = fs.downloadFiles(RuntimeContext.empty(), List.of("/a.bin"));

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).isSuccess()).isTrue();
        assertThat(responses.get(0).content()).isEqualTo(new byte[]{5, 5});
    }

    // ---------- 路径安全 ----------

    @Test
    void given_traversal_path_when_invoked_then_blocked_not_reaching_container() {
        assertThat(fs.exists(RuntimeContext.empty(), "/../etc/passwd")).isFalse();
        assertThat(fs.ls(RuntimeContext.empty(), "/a/../b").error()).contains("路径非法");
        assertThatThrownBy(() -> fs.write(RuntimeContext.empty(), "/../escape.md", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        // 未产生任何容器命令
        assertThat(exec.commands).isEmpty();
    }

    @Test
    void given_quote_in_filename_when_invoked_then_shell_escaped() {
        exec.responder = c -> ok("");

        fs.write(RuntimeContext.empty(), "/docs/it's.md", "内容");

        assertThat(exec.ran("cat > '/workspace/docs/it'\\''s.md'")).isTrue();
    }
}
