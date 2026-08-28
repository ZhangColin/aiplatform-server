package com.aieducenter.aiplatform.base.workspace.infrastructure.docker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assumptions;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.ProvisionedResource;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.cartisan.core.exception.ApplicationException;


import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Docker 后端真实验收（B0 §5：副作用以真实状态为准，不以事件自述）——
 * 本机 docker daemon 在则跑全链：建工作区 → 容器/网络/资源就绪 + .env 注入 →
 * exec → exposePort → 销毁级联。daemon 不在则跳过（CI 无 docker 时不红）。
 */
class DockerEnvironmentBackendTest {

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
    void given_dev_workspace_when_create_then_containers_network_env_ready() {
        requireDockerDaemon();
        WorkspaceId workspaceId = WorkspaceId.generate();

        provision = backend.createWorkspace(workspaceId, EnvKind.DEV);

        WorkspaceHandle handle = provision.handle();
        assertThat(handle.kind()).isEqualTo(EnvKind.DEV);
        assertThat(handle.containerName()).isEqualTo("ws-" + workspaceId.value() + "-dev");
        // dev 容器、pg、redis 真实存活（副作用以真实状态为准）
        List.of("ws-" + workspaceId.value() + "-dev",
                "pg-" + workspaceId.value(), "rd-" + workspaceId.value())
                .forEach(container ->
                        assertThat(docker("inspect", "-f", "{{.State.Running}}", container)
                                .stdout().trim()).isEqualTo("true"));
        // 资源清单与容器网络内连接串
        assertThat(provision.resources()).extracting(ProvisionedResource::kind)
                .containsExactlyInAnyOrder(MiddlewareKind.POSTGRESQL, MiddlewareKind.REDIS);
        ProvisionedResource pg = provision.resources().stream()
                .filter(r -> r.kind() == MiddlewareKind.POSTGRESQL).findFirst().orElseThrow();
        assertThat(pg.internalUrl()).startsWith("postgresql://ws" + workspaceId.value() + ":");
        assertThat(pg.internalUrl()).contains("@pg-" + workspaceId.value() + ":5432/ws" + workspaceId.value());
        // .env 已写入连接串（agent 读的就是它）
        ExecResult envFile = backend.exec(handle, "cat /workspace/.env");
        assertThat(envFile.exitCode()).isZero();
        assertThat(envFile.stdout()).contains("DATABASE_URL=" + pg.internalUrl());
        assertThat(envFile.stdout()).contains("REDIS_URL=redis://rd-" + workspaceId.value() + ":6379");
        // 中间件真实可服务（就绪等待生效，非事件自述）
        assertThat(docker("exec", "pg-" + workspaceId.value(), "pg_isready").exitCode()).isZero();
        assertThat(docker("exec", "rd-" + workspaceId.value(), "redis-cli", "ping").stdout().trim())
                .isEqualTo("PONG");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_created_workspace_when_exec_then_command_output_captured() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        ExecResult result = backend.exec(provision.handle(), "echo hello-workspace");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().trim()).isEqualTo("hello-workspace");
        // 非 0 退出码是命令失败，原样归一化不抛
        ExecResult failing = backend.exec(provision.handle(), "exit 3");
        assertThat(failing.exitCode()).isEqualTo(3);
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_created_workspace_when_expose_port_then_preview_url_responds_on_mapped_port() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        URI url = backend.exposePort(provision.handle(), EnvironmentBackend.DEV_PREVIEW_CONTAINER_PORT);

        assertThat(url.toString()).isEqualTo("http://localhost:" + provision.handle().previewPort() + "/");
        // 真实可访问（任何状态码都算已监听；空 workspace 挂 index 缺失返回 404）
        assertThatCode(() -> HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(url).GET().build(),
                        HttpResponse.BodyHandlers.discarding()))
                .doesNotThrowAnyException();
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_workspace_with_source_when_pack_source_then_real_tarball_without_secrets() throws Exception {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        // 工作区摆上源码事实 + 平台机密（.env）+ 可重建重物（node_modules）
        backend.exec(provision.handle(),
                "echo '<html>demo</html>' > /workspace/index.html"
                        + " && mkdir -p /workspace/node_modules/leftpad"
                        + " && echo junk > /workspace/node_modules/leftpad/index.js");

        byte[] tarball = backend.packSource(provision.handle());

        // gzip 魔数（真实 tar.gz，非空壳）
        assertThat(tarball.length).isGreaterThan(2);
        assertThat(tarball[0]).isEqualTo((byte) 0x1f);
        assertThat(tarball[1]).isEqualTo((byte) 0x8b);
        // 宿主侧解包清单（真实状态为准）：源码在、机密与重物不在
        Path tar = Files.createTempFile("aiplatform-source", ".tar.gz");
        String listing;
        try {
            Files.write(tar, tarball);
            listing = hostTarListing(tar);
        } finally {
            Files.deleteIfExists(tar);
        }
        assertThat(listing).contains("index.html");
        assertThat(listing).doesNotContain(".env");
        assertThat(listing).doesNotContain("node_modules");
    }

    @Test
    void given_non_dev_kind_when_create_then_rejected_before_any_side_effect() {
        // Phase A 仅 DEV（WSP_007）；拒绝发生在任何 docker 交互之前
        assertThatThrownBy(() -> backend.createWorkspace(WorkspaceId.generate(), EnvKind.TEST))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("暂不支持的环境类型");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_created_workspace_when_destroy_then_cascade_cleaned() {
        requireDockerDaemon();
        WorkspaceId workspaceId = WorkspaceId.generate();
        provision = backend.createWorkspace(workspaceId, EnvKind.DEV);
        backend.destroyWorkspace(provision.handle());
        provision = null; // 已清理，tearDown 不再兜底

        // inspect/network inspect 失败（非 0 退出码）= 已不存在
        assertResourcesGone(workspaceId);
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_network_create_fails_when_create_then_provisioned_resources_rolled_back() {
        requireDockerDaemon();
        WorkspaceId workspaceId = WorkspaceId.generate();
        DockerEnvironmentBackend failing = new FailingNetworkCreateBackend("simulated failure");

        assertThatThrownBy(() -> failing.createWorkspace(workspaceId, EnvKind.DEV))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("环境后端操作失败");

        // 置备中途失败已级联回滚：dev 容器/网络/卷无残留（#57 泄漏1验收）
        assertResourcesGone(workspaceId);
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_address_pool_exhausted_when_create_then_self_diagnosable_error_and_rolled_back() {
        requireDockerDaemon();
        WorkspaceId workspaceId = WorkspaceId.generate();
        DockerEnvironmentBackend failing = new FailingNetworkCreateBackend(
                "Error response from daemon: failed to allocate gateway: "
                        + "all predefined address pools have been fully subnetted");

        assertThatThrownBy(() -> failing.createWorkspace(workspaceId, EnvKind.DEV))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("地址池已耗尽");

        assertResourcesGone(workspaceId);
    }

    // ---------- 直连 docker CLI 的验证工具（真实状态为准） ----------

    /** 宿主侧 tar 清单（macOS/Linux 自带 tar）：解开包内容做事实核对。 */
    private static String hostTarListing(Path tarFile) throws Exception {
        Process p = new ProcessBuilder("tar", "tzf", tarFile.toString()).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
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

    private static boolean dockerAvailable() {
        return docker("version", "--format", "{{.Server.Version}}").ok();
    }

    /** 纯逻辑用例（如 kind 拒绝）不依赖 daemon，不进此门。 */
    private static void requireDockerDaemon() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实链路");
    }

    /** 断言按命名约定派生的容器/网络/卷均已不存在（inspect 非 0 = 已清）。 */
    private static void assertResourcesGone(WorkspaceId workspaceId) {
        String id = workspaceId.value();
        List.of("ws-" + id + "-dev", "pg-" + id, "rd-" + id, "net-" + id,
                "vol-ws-" + id + "-dev", "vol-pg-" + id)
                .forEach(name -> {
                    if (name.startsWith("net-")) {
                        assertThat(docker("network", "inspect", name).exitCode()).isNotZero();
                    } else if (name.startsWith("vol-")) {
                        assertThat(docker("volume", "inspect", name).exitCode()).isNotZero();
                    } else {
                        assertThat(docker("inspect", name).exitCode()).isNotZero();
                    }
                });
    }

    /** 注入 network create 失败的后端（覆写 runCapture 单条命令失败，余命令走真实 docker）。 */
    private static class FailingNetworkCreateBackend extends DockerEnvironmentBackend {

        private final String stderr;

        FailingNetworkCreateBackend(String stderr) {
            this.stderr = stderr;
        }

        @Override
        protected ExecResult runCapture(String... cmd) {
            if (cmd.length >= 3 && "docker".equals(cmd[0])
                    && "network".equals(cmd[1]) && "create".equals(cmd[2])) {
                return new ExecResult("", stderr, 1);
            }
            return super.runCapture(cmd);
        }
    }
}
