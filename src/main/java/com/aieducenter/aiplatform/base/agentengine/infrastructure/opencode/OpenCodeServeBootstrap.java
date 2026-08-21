package com.aieducenter.aiplatform.base.agentengine.infrastructure.opencode;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import lombok.extern.slf4j.Slf4j;

import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.AgentApiKeyResolver;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;

/**
 * opencode serve 运行时引导（片2a）：dev 容器内的无头 HTTP 服务即引擎接入点——
 * 片1 只占位端口映射，本类负责「未就绪即拉起」：
 *
 * <ol>
 *   <li>探活（带 Basic Auth 的 GET /global/health）：宿主 hostPort 直达容器 4096；</li>
 *   <li>平台重启后凭据丢失 → 从容器内密码文件读回（serve 进程随容器存活，不重启）；</li>
 *   <li>确实未起 → 写 provider 配置（classpath {@code docker/agent/opencode-provider.json}，
 *       key 以 {@code {env:DEEPSEEK_API_KEY}} 引用、经进程环境注入）→ 生成随机密码并
 *       落容器文件 → {@code docker exec -d} 起 serve → 探活等到就绪。</li>
 * </ol>
 *
 * <p>幂等：多任务并发首跑时重复拉起以探活收敛（serve 端口占用即第二次起不来，
 * 先落定的探活通过即胜出）。</p>
 */
@Component
@Adapter(PortType.CLIENT)
@Slf4j
public class OpenCodeServeBootstrap {

    /** serve 在容器内的监听端口（镜像与后端约定的单一事实，EnvironmentBackend 常量）。 */
    private static final int SERVE_PORT = EnvironmentBackend.DEV_ENGINE_CONTAINER_PORT;

    private static final String CONFIG_PATH = "/root/.config/opencode/opencode.json";
    private static final String PASSWORD_PATH = "/root/.opencode/serve-password";
    private static final String PROVIDER_CONFIG_RESOURCE = "docker/agent/opencode-provider.json";

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);

    private final EnvironmentBackend environmentBackend;
    private final AgentApiKeyResolver apiKeyResolver;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final SecureRandom random = new SecureRandom();
    /** workspaceId → serve 密码（进程内缓存；重启后从容器文件读回）。 */
    private final Map<Long, String> passwordCache = new ConcurrentHashMap<>();

    public OpenCodeServeBootstrap(EnvironmentBackend environmentBackend,
                                  AgentApiKeyResolver apiKeyResolver) {
        this.environmentBackend = environmentBackend;
        this.apiKeyResolver = apiKeyResolver;
    }

    /**
     * 确保 serve 就绪并取接入点（baseUrl + Basic Auth 密码）。拉起失败抛 AGT_004。
     */
    public ServeEndpoint ensureRunning(WorkspaceHandle handle) {
        String cached = passwordCache.get(handle.workspaceId().id());
        if (cached != null && probe(handle, cached)) {
            return endpoint(handle, cached);
        }
        // 平台重启（缓存丢失）或 serve 未起：先试容器内密码文件（serve 可能还活着）
        String persisted = readPassword(handle);
        if (persisted != null && probe(handle, persisted)) {
            passwordCache.put(handle.workspaceId().id(), persisted);
            return endpoint(handle, persisted);
        }
        startServe(handle);
        String password = readPassword(handle);
        if (password == null || !waitForReady(handle, password)) {
            throw new ApplicationException(AgentEngineMessage.ENGINE_REQUEST_FAILED,
                    "opencode serve 拉起失败（容器 " + handle.containerName() + "）");
        }
        passwordCache.put(handle.workspaceId().id(), password);
        return endpoint(handle, password);
    }

    /**
     * serve 是否就绪（health 端点的实现缝：拉起路径的探活同源）。
     */
    public boolean isRunning(WorkspaceHandle handle) {
        String cached = passwordCache.get(handle.workspaceId().id());
        return cached != null && probe(handle, cached)
                || probeWithPersistedPassword(handle);
    }

    private boolean probeWithPersistedPassword(WorkspaceHandle handle) {
        String persisted = readPassword(handle);
        if (persisted != null && probe(handle, persisted)) {
            passwordCache.put(handle.workspaceId().id(), persisted);
            return true;
        }
        return false;
    }

    private ServeEndpoint endpoint(WorkspaceHandle handle, String password) {
        return new ServeEndpoint("http://localhost:" + handle.hostPort(), password);
    }

    private boolean probe(WorkspaceHandle handle, String password) {
        if (handle.hostPort() == 0) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + handle.hostPort() + "/global/health"))
                    .header("Authorization", new ServeEndpoint(null, password).authHeader())
                    .timeout(PROBE_TIMEOUT)
                    .GET().build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitForReady(WorkspaceHandle handle, String password) {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (probe(handle, password)) {
                return true;
            }
            sleep();
        }
        return false;
    }

    private void startServe(WorkspaceHandle handle) {
        writeProviderConfig(handle);
        String password = Long.toUnsignedString(random.nextLong(), 36)
                + Long.toUnsignedString(random.nextLong(), 36);
        // 密码先落容器文件再起 serve（重启读回的锚点；@前缀防 ps 窥探属锦上添花，不追求）
        ExecResult stored = environmentBackend.exec(handle, "mkdir -p $(dirname " + PASSWORD_PATH
                + ") && printf '%s' '" + password + "' > " + PASSWORD_PATH);
        if (!stored.ok()) {
            throw new ApplicationException(AgentEngineMessage.ENGINE_REQUEST_FAILED,
                    "写入 opencode serve 密码文件失败: " + stored.stderr());
        }
        ExecResult started = environmentBackend.exec(handle,
                "OPENCODE_SERVER_PASSWORD='" + password + "' " + apiKeyResolver.envPrefix()
                        + "opencode serve --hostname 0.0.0.0 --port " + SERVE_PORT
                        + " > /tmp/opencode-serve.log 2>&1 &");
        if (!started.ok()) {
            throw new ApplicationException(AgentEngineMessage.ENGINE_REQUEST_FAILED,
                    "启动 opencode serve 失败: " + started.stderr());
        }
        log.info("[agentengine] 容器 {} 内 opencode serve 拉起（端口 {}）",
                handle.containerName(), SERVE_PORT);
    }

    private void writeProviderConfig(WorkspaceHandle handle) {
        try (InputStream in = new ClassPathResource(PROVIDER_CONFIG_RESOURCE).getInputStream()) {
            String config = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String b64 = Base64.getEncoder().encodeToString(config.getBytes(StandardCharsets.UTF_8));
            ExecResult r = environmentBackend.exec(handle, "mkdir -p $(dirname " + CONFIG_PATH
                    + ") && printf '%s' '" + b64 + "' | base64 -d > " + CONFIG_PATH);
            if (!r.ok()) {
                throw new ApplicationException(AgentEngineMessage.ENGINE_REQUEST_FAILED,
                        "写入 opencode provider 配置失败: " + r.stderr());
            }
        } catch (ApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new ApplicationException(AgentEngineMessage.ENGINE_REQUEST_FAILED,
                    "读取 opencode provider 配置资源失败: " + e.getMessage());
        }
    }

    private String readPassword(WorkspaceHandle handle) {
        ExecResult r = environmentBackend.exec(handle, "cat " + PASSWORD_PATH + " 2>/dev/null");
        if (!r.ok() || r.stdout().isBlank()) {
            return null;
        }
        return r.stdout().strip();
    }

    private void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationException(AgentEngineMessage.ENGINE_REQUEST_FAILED,
                    "等待 opencode serve 就绪被中断");
        }
    }

    /**
     * serve 接入点：baseUrl + Basic Auth 密码（适配器 HTTP 调用的凭据束）。
     */
    public record ServeEndpoint(String baseUrl, String password) {

        /** Basic Auth 头（user 固定 opencode，凭据即 serve 密码）。 */
        public String authHeader() {
            String cred = "opencode:" + password;
            return "Basic " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
        }
    }
}
