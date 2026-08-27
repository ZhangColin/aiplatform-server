package com.aieducenter.aiplatform.business.project.application;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext;
import com.aieducenter.aiplatform.base.chatagent.application.ChatAgentAppService;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentReply;
import com.aieducenter.aiplatform.base.chatagent.domain.model.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 项目取名服务（#39，grilling 定案「异步取名」）：创建即落占位名
 * {@link Project#PLACEHOLDER_NAME}，创建后经对话智能体基座的一次静默轻调用
 * （AgentScope，{@link ChatAgentAppService#converseSilently}——无 SSE 帧、无等待点）
 * 据 requirement 生成项目名并落位；落库即发 {@code project-renamed}（#52 触达补口：
 * 前端失效 projects 域重拉，停留中的页面上名字静默浮现，ChatGPT 式——守卫不覆写
 * 与失败保占位均不发）。⚠️ 红线：禁止字符串截取派生——净化不过关/引擎失败/超时
 * 一律保占位（经改名端点 #43 可改），绝无「requirement 前 N 字符」兜底。
 *
 * <p>时机与线程：创建响应不等取名（REST 即时返回）；本服务自持单线程执行器
 * fire-and-forget——不占 chatagent 续跑闸（BA 开场问答卡不被取名排队拖慢），串行
 * 又是必要的：全部取名共用同一缓存 HarnessAgent 实例（工厂按 prompt/工作区键
 * 复用），单线程保证同一 agent 上不并发 streamEvents（并发安全性上游未背书）。
 * 落位守卫：只顶替仍是占位名的项目（取名在飞时用户已改名则不覆写）；项目已删
 * 静默跳过。计量：subject=projectId + role=NAMING 用途标记（非 RolePreset，byRole
 * 展示名空，前端落「—」桶）。命名质量迭代（提示词调优）票外另行。</p>
 */
@Service
@Slf4j
public class ProjectNamingAppService implements DisposableBean {

    /** 取名会话标识派生前缀（projectId → naming-{projectId}，一次性会话）。 */
    public static final String SESSION_PREFIX = "naming-";

    /** 取名用量的 role 维度用途标记（与 FIX/RESUME 同型：非 RolePreset 的标记桶）。 */
    public static final String NAMING_ROLE_DIM = "NAMING";

    /**
     * 取名协议（只输出名称本身；调优票外——能用即可）：4-12 字中文项目名，
     * 禁解释/引号/结尾标点/工具调用（本地兜底工作区，不碰项目 dev 工作区）。
     */
    private static final String NAMING_SYSTEM_PROMPT =
            "你是软件项目的命名助手。用户会给出一段项目需求描述，请据此为项目起一个简洁、贴切、易读的中文项目名。"
                    + "规则：名字 4-12 个字；直接输出名字本身，不要解释、不要引号、不要结尾标点；不调用任何工具。";

    /** 净化时剥的包裹字符（成对引号/加粗星号/结尾标点——模型偶发包裹，结构性清理）。 */
    private static final String WRAPPERS = "「」『』“”‘’\"'`*。．.！!？?~～";

    private final ChatAgentAppService chatAgentAppService;
    private final ProjectRepository projectRepository;
    private final PlatformNotificationAppService notificationAppService;
    /** 提交通道（生产=虚拟线程池；测试=直通同步）。 */
    private final Executor executor;
    /** 生产执行器生命周期（测试注入直通道时为 null）。 */
    private final ExecutorService ownedExecutor;

    @Autowired
    public ProjectNamingAppService(ChatAgentAppService chatAgentAppService,
            ProjectRepository projectRepository,
            PlatformNotificationAppService notificationAppService) {
        this.chatAgentAppService = chatAgentAppService;
        this.projectRepository = projectRepository;
        this.notificationAppService = notificationAppService;
        this.ownedExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "project-naming");
            thread.setDaemon(true);
            return thread;
        });
        this.executor = ownedExecutor;
    }

    /** 测试便利构造（直通道，无生命周期）。 */
    ProjectNamingAppService(ChatAgentAppService chatAgentAppService,
            ProjectRepository projectRepository,
            PlatformNotificationAppService notificationAppService, Executor executor) {
        this.chatAgentAppService = chatAgentAppService;
        this.projectRepository = projectRepository;
        this.notificationAppService = notificationAppService;
        this.executor = executor;
        this.ownedExecutor = null;
    }

    /**
     * 异步取名（创建编排建项目后触发）：fire-and-forget——任何失败只记日志保占位，
     * 绝不炸创建路径；requirement 为空不取名（无输入可依，占位即终态，改名端点可改）。
     */
    public void nameAsync(Long projectId, String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return;
        }
        executor.execute(() -> nameQuietly(projectId, requirement));
    }

    @Override
    public void destroy() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    // ---------- 内部 ----------

    /** 取名 + 落位（吞异常版）：失败/净化不过关保占位（见类注释）。 */
    private void nameQuietly(Long projectId, String requirement) {
        String generated;
        try {
            generated = converseForName(projectId, requirement);
        } catch (RuntimeException e) {
            log.warn("项目 {} LLM 取名失败（保占位名，经改名端点可改）：{}", projectId, e.getMessage());
            return;
        }
        if (generated == null) {
            log.info("项目 {} LLM 取名输出未过净化（保占位名）", projectId);
            return;
        }
        projectRepository.findById(projectId).ifPresent(project -> {
            if (project.renameIfPlaceholder(generated)) {
                projectRepository.save(project);
                // #52 触达补口：落定即广播（取名跑在自有执行器线程，save 自动提交后
                // 发射即满足 ADR-0001「事务提交后发射」；projectId 即本方法入参）
                notificationAppService.publish(ProjectEventTypes.PROJECT_RENAMED, Map.of(
                        ProjectEventTypes.PROJECT_ID_FIELD, projectId.toString(),
                        ProjectEventTypes.PROJECT_NAME_FIELD, project.getName()));
            } else {
                log.info("项目 {} 已非占位名（用户改名或取名已完成），本次取名不覆写", projectId);
            }
        });
    }

    /** 静默轻调用取名：naming-{projectId} 一次性会话、本地工作区、缺省 flash 档模型。 */
    private String converseForName(Long projectId, String requirement) {
        ChatAgentCommand command = new ChatAgentCommand(
                AgentRunContext.newRunId(),
                requirement,
                NAMING_SYSTEM_PROMPT,
                null, // 模型取适配器缺省（对话轨道 flash 档，快且省）
                SESSION_PREFIX + projectId,
                null, // 一次性会话，无 (userId, sessionId) 槽位复用语义
                new UsageContext(Long.toString(projectId),
                        Map.of(ProjectAgentTaskAppService.DIM_ROLE, NAMING_ROLE_DIM)),
                null, // 本地兜底工作区：取名不读写项目 dev 工作区
                Map.of());
        ChatAgentReply reply = chatAgentAppService.converseSilently(command);
        return sanitize(reply.text());
    }

    /**
     * 输出净化（结构性清理，非截取）：取首个非空行 → 剥包裹引号/加粗/结尾标点 →
     * 空或超上限弃用（返回 null，调用方保占位）。红线：不回退 requirement 截取。
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.lines().map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(null);
        if (line == null) {
            return null;
        }
        while (!line.isEmpty() && WRAPPERS.indexOf(line.charAt(line.length() - 1)) >= 0) {
            line = line.substring(0, line.length() - 1).trim();
        }
        int start = 0;
        int end = line.length();
        while (start < end && WRAPPERS.indexOf(line.charAt(start)) >= 0) {
            start++;
        }
        line = line.substring(start, end).trim();
        if (line.isEmpty() || line.length() > Project.NAME_MAX_LENGTH) {
            return null; // 超限弃用不截断（红线同源；上限单一事实源在聚合）
        }
        return line;
    }
}
