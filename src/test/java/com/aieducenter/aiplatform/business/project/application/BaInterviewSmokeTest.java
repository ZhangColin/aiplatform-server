package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;
import com.aieducenter.aiplatform.base.chatagent.infrastructure.ChatAgentWorkspaceClient;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectAgentTaskResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * #40 BA 访谈循环真模型冒烟（#49 补 PRD 产出链；DEEPSEEK_API_KEY 未设或 docker
 * daemon 不在整类跳过）：编排全真链（真模型 + 真 dev 容器 + 真 PG 落库/settle
 * 续跑），仅 SSE 发射边（无订阅者的广播口）与工作区句柄解析两处 mock 收口观测。
 *
 * <p>验收口径：一句话开场 → 至少两轮实质提问（QUESTION 载荷带前端问答卡形状，
 * 经 PG JSON 落库往返）→ 答卡 settle 续跑 → 催促收敛（自由补充通道，BA 停止提问）
 * → savePrd 产出 PRD（工作区文件 + 状态位 + document-updated + G1 门就绪翻真，
 * 修订再执行三更新）→ 同会话上下文延续；计量落 UsageEvent（engine=agentscope，
 * dims.role=BA）。</p>
 */
@SpringBootTest
class BaInterviewSmokeTest {

    private static final Duration TURN_DEADLINE = Duration.ofSeconds(150);

    @Autowired
    private BaInterviewAppService appService;

    @Autowired
    private AgentWaitAppService waitAppService;

    @Autowired
    private ProjectQueryAppService queryAppService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IterationRepository iterationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** SSE 发射边收口：捕获全帧（真实链路无订阅者，发射本身是观测缝）。 */
    @MockitoBean
    private AgentStreamAppService streamAppService;

    /** 通知通道发射边收口（#49 document-updated 观测；BA 访谈链路无其余通知方）。 */
    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    /** 工作区句柄解析收口 → 指向真实 dev 容器（docker exec 文件面为真）。 */
    @MockitoBean
    private ChatAgentWorkspaceClient chatWorkspaceClient;

    /** settle 链的引擎侧句柄解析（同容器）。 */
    @MockitoBean
    private WorkspaceHandleClient engineWorkspaceHandleClient;

    private final DockerEnvironmentBackend backend = new DockerEnvironmentBackend();
    private final ConcurrentLinkedQueue<Frame> frames = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Notify> notifies = new ConcurrentLinkedQueue<>();

    private WorkspaceProvision provision;
    private Long projectId;
    private String sessionId;

    private record Frame(String type, Map<String, Object> payload) {
        String runId() {
            Object runId = payload.get("runId");
            return runId != null ? runId.toString() : "";
        }
    }

    private record Notify(String type, Map<String, Object> payload) {
    }

    @BeforeAll
    static void requireEnvironment() {
        Assumptions.assumeTrue(
                System.getenv("DEEPSEEK_API_KEY") != null
                        && !System.getenv("DEEPSEEK_API_KEY").isBlank(),
                "DEEPSEEK_API_KEY 未设置，跳过真模型 BA 访谈冒烟");
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @AfterEach
    void tearDown() {
        if (sessionId != null) {
            jdbcTemplate.update("DELETE FROM agt_pending_waits WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM agt_agent_sessions WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM met_usage_events WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM cat_agent_state WHERE session_id = ?", sessionId);
        }
        if (projectId != null) {
            jdbcTemplate.update("DELETE FROM prj_iterations WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
        if (provision != null) {
            backend.destroyWorkspace(provision.handle());
        }
    }

    @Test
    @Timeout(500)
    void given_one_liner_when_interview_then_multi_round_then_urged_convergence_with_context() {
        // 真实 dev 容器 + 项目/期落库（编排面全真）
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        WorkspaceHandle handle = provision.handle();
        String workspaceId = String.valueOf(handle.workspaceId().id());
        when(chatWorkspaceClient.handleOf(workspaceId)).thenReturn(handle);
        when(engineWorkspaceHandleClient.handleOf(workspaceId)).thenReturn(handle);
        doAnswer(invocation -> {
            frames.add(new Frame(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(streamAppService).publish(any(), any());
        doAnswer(invocation -> {
            notifies.add(new Notify(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(notificationAppService).publish(any(), any());
        Project project = projectRepository.save(Project.create("冒烟官网", null, "opencode",
                Long.parseLong(workspaceId), null));
        projectId = project.getId();
        iterationRepository.save(Iteration.open(projectId, Iteration.FIRST_SEQ,
                ProjectMainChain.firstStage()));
        sessionId = BaInterviewAppService.SESSION_PREFIX + projectId;

        // 1) 一句话开场 → BA 至少一轮实质提问（QUESTION 等待点，前端问答卡形状经 PG 往返）
        ProjectAgentTaskResponse first = appService.runInterviewTurn(projectId, "做一个企业官网");
        String question1 = awaitQuestionWaitOf(first.runId());
        Map<String, Object> body1 = waitBody(question1);
        assertThat(framesOf(AgentEventTypes.ROLE_ASSIGNED)).isNotEmpty();
        Map<String, Object> asked = firstQuestionOf(body1);
        assertThat(String.valueOf(asked.get("question"))).as("问题载荷：%s", asked).isNotBlank();
        assertThat(asked).containsEntry("multiple", false).containsEntry("custom", true);
        // PRD 产出前 G1 门不 ready（#49 谓词：计数已达标——首问即计数，缺口在 PRD）
        assertThat(queryAppService.detail(projectId).gate().ready()).isFalse();

        // 2) 答卡 settle → 续跑 → 第二轮提问（答复循环 ≥2 轮：settle 续跑在同一 run 上
        // 再挂起，新等待点新 waitId；答复刻意只覆盖目标用户——范围/约束仍缺，访谈必续）
        settle(question1, "目标用户是海外企业客户，主要看公司与产品介绍；其余方面我也不确定，你继续问");
        String question2 = awaitNextQuestionWaitOf(first.runId(), question1);
        settle(question2, "要有中英文两个语言版本，范围就官网本体不要商城，风格简洁专业");

        // 3) 催促收敛（自由补充通道）：模型可能多问一轮（催促遵从非确定），复刻
        // 真实用户反复催促——每见新问即以催促文本作答再催（上限 4 轮必收敛）；
        // 在悬时化解锚回原 run，已收口则催促开新轮（响应 runId 即锚）
        List<String> seenWaits = new java.util.ArrayList<>(List.of(question1, question2));
        for (int i = 0; i < 4; i++) {
            ProjectAgentTaskResponse urged = appService.runInterviewTurn(projectId,
                    "不要再继续提问了，现在就结束访谈，直接产出 PRD");
            // 双锚等待：新轮路径帧在催促 run；化解/竞态折入路径帧续在原 run（响应
            // 的 runId 在折入时是死锚——见 BaInterviewAppService javadoc 竞态口径）
            String next = awaitResumeOutcome(java.util.Set.of(first.runId(), urged.runId()),
                    seenWaits);
            if ("finished".equals(next)) {
                break;
            }
            seenWaits.add(next); // 又问了一轮——继续催
        }
        awaitNoPendingQuestions(); // settle/终态联动（expireRun）收尾窗口

        // 4) 判定明确/催促收敛 → savePrd（#49）：工作区文件 + 状态位 + document-updated
        // + G1 门就绪翻真（PRD 读端点直读工作区文件——编码智能体同视图）
        awaitPrdProduced(1);
        assertThat(prdBitOf(projectId)).isNotNull();
        PrdResponse prd = queryAppService.prd(projectId);
        assertThat(prd.content()).as("PRD 正文（真模型产出）").isNotBlank();
        assertThat(prd.updatedAt()).isNotNull();
        assertThat(queryAppService.detail(projectId).gate().ready()).isTrue();

        // 5) PRD 修订（savePrd 再次执行）：三更新——文件/状态位/事件
        java.time.LocalDateTime firstBit = prdBitOf(projectId);
        ProjectAgentTaskResponse revision = appService.runInterviewTurn(projectId,
                "需求有更新：官网要增加一个博客板块，请修订并重新保存 PRD");
        awaitRunEnd(revision.runId());
        awaitPrdProduced(2);
        assertThat(prdBitOf(projectId)).isAfterOrEqualTo(firstBit);
        assertThat(queryAppService.prd(projectId).content()).isNotBlank();
    }

    /** 有界等待 document-updated 触达 ≥ n 次（#49：savePrd 每次执行必发；工具在
     *  run 收口前执行，收敛轮结束即可等，留界兜底层间延迟）。 */
    private void awaitPrdProduced(int atLeast) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (documentUpdatedCount() >= atLeast && prdBitOf(projectId) != null) {
                return;
            }
            sleepQuietly();
        }
        assertThat(documentUpdatedCount()).as("document-updated 触达（已捕获：%s）",
                notifies.stream().map(n -> n.type()).toList()).isGreaterThanOrEqualTo(atLeast);
        assertThat(prdBitOf(projectId)).as("PRD 状态位").isNotNull();
    }

    private long documentUpdatedCount() {
        return notifies.stream()
                .filter(n -> ProjectEventTypes.DOCUMENT_UPDATED.equals(n.type())).count();
    }

    /** 项目「PRD 已产出」状态位（savePrd 落盘回调写入）。 */
    private java.time.LocalDateTime prdBitOf(Long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT prd_produced_at FROM prj_projects WHERE id = ?",
                java.time.LocalDateTime.class, projectId);
    }

    /** 有界等待会话在悬问答归零（催促收敛的收尾：settle 落库与终态联动有竞态窗）。 */
    private void awaitNoPendingQuestions() {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (pendingQuestionCount() == 0) {
                return;
            }
            sleepQuietly();
        }
        assertThat(pendingQuestionCount()).as("催促后不应再挂起新问题").isZero();

        // 4) 同会话上下文延续：访谈答复在上下文中可回溯（自由补充不丢）
        ProjectAgentTaskResponse recall = appService.runInterviewTurn(projectId,
                "回顾访谈：我把目标用户答成了什么？只回答「海外企业客户」相关的答案要点，不要再提问");
        awaitRunEnd(recall.runId());
        assertThat(textOf(recall.runId())).contains("海外");

        // 5) 计量：BA 对话用量落 UsageEvent（engine=agentscope、role=BA、归属项目）
        Integer usageRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM met_usage_events WHERE session_id = ?", Integer.class,
                sessionId);
        assertThat(usageRows).isPositive();
        Map<String, Object> usage = jdbcTemplate.queryForMap(
                "SELECT engine, subject, dims FROM met_usage_events"
                        + " WHERE session_id = ? LIMIT 1", sessionId);
        assertThat(usage.get("engine")).isEqualTo("agentscope");
        assertThat(String.valueOf(usage.get("subject"))).isEqualTo(projectId.toString());
        assertThat(String.valueOf(usage.get("dims"))).contains("BA");
    }

    // ---------- 内部 ----------

    /** 等待该 run 的 QUESTION 挂起（流帧捕获 → waitId → 落库行）。 */
    private String awaitQuestionWaitOf(String runId) {
        Frame wait = awaitFrame(runId, AgentEventTypes.WAIT_RAISED);
        Object waitId = wait.payload().get(AgentEventTypes.WAIT_ID_FIELD);
        assertThat(waitId).as("wait-raised 补发应带 waitId").isNotNull();
        assertThat(wait.payload()).containsEntry(AgentEventTypes.WAIT_KIND_FIELD, "QUESTION");
        return waitId.toString();
    }

    /** 等待该 run 的下一个 QUESTION（settle 续跑同 run 再挂起——按排除已见 waitId 区分）。 */
    private String awaitNextQuestionWaitOf(String runId, String excludeWaitId) {
        long deadline = System.nanoTime() + TURN_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            for (Frame frame : frames) {
                if (!runId.equals(frame.runId())
                        || !AgentEventTypes.WAIT_RAISED.equals(frame.type())) {
                    continue;
                }
                Object waitId = frame.payload().get(AgentEventTypes.WAIT_ID_FIELD);
                if (waitId != null && !excludeWaitId.equals(waitId.toString())) {
                    assertThat(frame.payload())
                            .containsEntry(AgentEventTypes.WAIT_KIND_FIELD, "QUESTION");
                    return waitId.toString();
                }
            }
            sleepQuietly();
        }
        throw new AssertionError("settle 续跑未再挂起提问（run=" + runId + "）——访谈在第二轮前收敛");
    }

    /** 等待该 run 收口（task-finish；error 视为失败）。 */
    private void awaitRunEnd(String runId) {
        Frame end = awaitFrame(runId, AgentEventTypes.TASK_FINISH, AgentEventTypes.ERROR);
        assertThat(end.type()).as("run %s 异常收口：%s", runId, end.payload())
                .isEqualTo(AgentEventTypes.TASK_FINISH);
    }

    /** 续跑出结果（跨候选 run 锚集）：再挂起（返回新 waitId，排除已见）或收口
     * （"finished"；error 直接失败）。折入路径的帧在原 run、新轮路径在催促 run——
     * 锚集并收两者。 */
    private String awaitResumeOutcome(java.util.Set<String> runIds, List<String> seenWaitIds) {
        long deadline = System.nanoTime() + TURN_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            for (Frame frame : frames) {
                if (!runIds.contains(frame.runId())) {
                    continue;
                }
                if (AgentEventTypes.ERROR.equals(frame.type())) {
                    throw new AssertionError("run 异常收口：" + frame.payload());
                }
                if (AgentEventTypes.TASK_FINISH.equals(frame.type())) {
                    return "finished";
                }
                if (AgentEventTypes.WAIT_RAISED.equals(frame.type())) {
                    Object waitId = frame.payload().get(AgentEventTypes.WAIT_ID_FIELD);
                    if (waitId != null && !seenWaitIds.contains(waitId.toString())) {
                        return waitId.toString();
                    }
                }
            }
            sleepQuietly();
        }
        throw new AssertionError("续跑无结果超时（runs=" + runIds + "）");
    }

    private int pendingQuestionCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agt_pending_waits WHERE session_id = ? AND status = 1"
                        + " AND kind = 1", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    private Frame awaitFrame(String runId, String... types) {
        long deadline = System.nanoTime() + TURN_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            for (Frame frame : frames) {
                if (runId.equals(frame.runId()) && List.of(types).contains(frame.type())) {
                    return frame;
                }
            }
            sleepQuietly();
        }
        throw new AssertionError("等待帧超时（" + List.of(types) + " run=" + runId
                + "），已捕获：" + frames.stream().map(f -> f.type() + "@" + f.runId()).toList());
    }

    /** 该 run 文本增量拼接（对话可见面）。 */
    private String textOf(String runId) {
        StringBuilder text = new StringBuilder();
        for (Frame frame : frames) {
            if (runId.equals(frame.runId()) && "text".equals(frame.type())) {
                Object data = frame.payload().get("data");
                if (data instanceof Map<?, ?> map && map.get("delta") != null) {
                    text.append(map.get("delta"));
                }
            }
        }
        return text.toString();
    }

    private List<Frame> framesOf(String type) {
        return frames.stream().filter(f -> type.equals(f.type())).toList();
    }

    private void settle(String waitId, String answer) {
        waitAppService.settle(String.valueOf(provision.handle().workspaceId().id()), waitId,
                new WaitSettleCommand(WaitSettleCommand.TYPE_ANSWER, List.of(List.of(answer)),
                        null, null));
    }

    /** 等待点 body（PG 落库行——JSON 往返后的前端问答卡载荷；jsonb 经 jdbc 呈 PGobject/串）。 */
    private Map<String, Object> waitBody(String waitId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT body FROM agt_pending_waits WHERE wait_id = ?", waitId);
            if (!rows.isEmpty() && rows.get(0).get("body") != null) {
                return parseBody(rows.get(0).get("body"));
            }
            sleepQuietly();
        }
        throw new AssertionError("等待点 body 读不到：" + waitId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBody(Object raw) {
        try {
            if (raw instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return new ObjectMapper().readValue(String.valueOf(raw), Map.class);
        } catch (java.io.IOException e) {
            throw new AssertionError("等待点 body 解析失败: " + raw, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstQuestionOf(Map<String, Object> body) {
        Object questions = body.get("questions");
        assertThat(questions).as("QUESTION body 应带 questions 数组（前端问答卡形状）").isNotNull();
        return ((List<Map<String, Object>>) questions).get(0);
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待被中断", e);
        }
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
