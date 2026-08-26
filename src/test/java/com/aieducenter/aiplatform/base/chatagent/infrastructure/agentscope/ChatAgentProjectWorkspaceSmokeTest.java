package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;

/**
 * 工作区桥真实验收（#45：副作用以真实状态为准，B0 §5 口径）：真 dev 工作区 +
 * {@link DockerExecFilesystem} 全链——对话智能体写 docs/PRD.md → 容器内可见 →
 * 源码包（packSource 同一打包口径）含该文件（BA 写 PRD 的基础）。daemon 不在
 * 则跳过（CI 无 docker 时不红）。
 */
class ChatAgentProjectWorkspaceSmokeTest {

    private static final int PROBE_TIMEOUT_SECONDS = 240;

    private final DockerEnvironmentBackend backend = new DockerEnvironmentBackend();

    private WorkspaceProvision provision;

    @AfterEach
    void tearDown() {
        // 断言失败也不留容器：按命名约定兜底级联清理
        if (provision != null) {
            backend.destroyWorkspace(provision.handle());
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_dev_workspace_when_agent_writes_prd_then_file_lands_in_source_package() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        DockerExecFilesystem fs = new DockerExecFilesystem(provision.handle().containerName());
        RuntimeContext rc = RuntimeContext.empty();

        // 对话智能体视角：写 PRD（create-only）→ 读回一致 → 目录/检索可用
        WriteResult written = fs.write(rc, "/docs/PRD.md", "# 官网 PRD\n\n目标：三页官网。\n");
        assertThat(written.isSuccess()).as("写入应成功：%s", written.error()).isTrue();

        ReadResult read = fs.read(rc, "/docs/PRD.md", 0, 0);
        assertThat(read.isSuccess()).isTrue();
        assertThat(read.fileData().content()).isEqualTo("# 官网 PRD\n\n目标：三页官网。\n");

        LsResult docs = fs.ls(rc, "/docs");
        assertThat(docs.isSuccess()).isTrue();
        assertThat(docs.entries()).extracting(e -> e.path()).contains("/docs/PRD.md");

        assertThat(fs.grep(rc, "官网", "/docs", null).matches()).hasSize(2);
        assertThat(fs.glob(rc, "**/*.md", "/").matches())
                .extracting(m -> m.path()).contains("/docs/PRD.md");

        // 验收正身：源码包（交付口径，tar.gz）含智能体写入的文件（tar 头文件名为明文）
        byte[] sourcePackage = backend.packSource(provision.handle());
        assertThat(sourcePackage).isNotEmpty();
        assertThat(ungzip(sourcePackage)).contains("docs/PRD.md");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_written_prd_when_write_again_then_create_only_rejected() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        DockerExecFilesystem fs = new DockerExecFilesystem(provision.handle().containerName());
        RuntimeContext rc = RuntimeContext.empty();

        assertThat(fs.write(rc, "/docs/PRD.md", "v1").isSuccess()).isTrue();
        WriteResult second = fs.write(rc, "/docs/PRD.md", "v2");

        assertThat(second.isSuccess()).isFalse();
        assertThat(second.error()).contains("already exists");
        assertThat(fs.read(rc, "/docs/PRD.md", 0, 0).fileData().content()).isEqualTo("v1");
    }

    // ---------- 门卫 ----------

    private static String ungzip(byte[] gzipped) {
        try (var in = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(gzipped))) {
            return new String(in.readAllBytes());
        }
        catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void requireDockerDaemon() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实链路");
    }

    private static boolean dockerAvailable() {
        return docker("version", "--format", "{{.Server.Version}}").ok();
    }

    private static ExecResult docker(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process p = new ProcessBuilder(cmd).start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            int code = p.waitFor();
            return new ExecResult(out, err, code);
        } catch (Exception e) {
            return new ExecResult("", String.valueOf(e.getMessage()), 1);
        }
    }
}
