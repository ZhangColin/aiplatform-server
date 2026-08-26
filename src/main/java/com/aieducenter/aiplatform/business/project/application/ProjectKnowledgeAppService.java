package com.aieducenter.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.base.process.domain.model.StageEntry;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Confirmation;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 知识沉淀编排（A5 §1/§3，票 #28）：五类素材的采集成形（A5 §1 表的唯一落点——
 * kind / source_ref / chunk 形态集中一处）+ runTask 检索注入单点。入库/检索/清理
 * 的存储语义归 base.knowledge（{@link KnowledgePort}），「哪个阶段产出什么、按
 * 什么粒度切」是业务知识，在此落定。
 *
 * <p><b>降级契约（A5 §1）</b>：摄取一律失败不炸——记日志跳过、不阻断主流程
 * （调用点全在编排的事务块之外，AFTER_COMMIT 语义由调用方保证）；embedding
 * 不可用由底座先行降级（旧块保留/检索空列表）。检索注入同样降级为空注入——
 * runTask 主流程不因知识库抖动受阻。search 端点例外：异常照抛（dev 视角要
 * 看到真实错误），空 query/非正 topK 由底座 400 KNW_ 拒绝。</p>
 *
 * <p><b>注入策略（A5 §3）</b>：不过滤、全局跨项目、纯相似 topK（可配
 * {@code app.knowledge.top-k}，默认 5）；query = 任务 prompt 全文（超长截断）；
 * 命中为空不注入也不发 SSE；OPC 人工任务与权限答复不经此（无 agent run）。</p>
 */
@Service
@Slf4j
public class ProjectKnowledgeAppService {

    /** 五类素材 kind（A5 §1；对底座不透明，展示与幂等键用）。 */
    public static final String KIND_ARTIFACT = "ARTIFACT";
    public static final String KIND_QA = "QA";
    public static final String KIND_FEEDBACK = "FEEDBACK";
    public static final String KIND_TEST_REPORT = "TEST_REPORT";
    public static final String KIND_BUG = "BUG";

    /** 段落级分块目标长度（A5 §1「~800 字符，实现定」）。 */
    static final int CHUNK_TARGET_CHARS = 800;

    /** 注入检索 query 上限（A5 §3「超长截断，上限实现定」；bge-small-zh 512 维模型）。 */
    static final int MAX_QUERY_CHARS = 2000;

    /** 展示字段 title 的列宽上限（knw_chunks.title VARCHAR(300)）。 */
    private static final int TITLE_MAX_CHARS = 300;

    /** 产物文件在工作区内的根路径（dev 镜像与角色卡约定的单一事实，RolePreset 同源）。 */
    private static final String WORKSPACE_ROOT = "/workspace/";

    private final KnowledgePort knowledgePort;
    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final AgentStreamAppService streamAppService;
    private final int injectTopK;

    public ProjectKnowledgeAppService(KnowledgePort knowledgePort,
                                      ProjectRepository projectRepository,
                                      WorkspaceLifecycleAppService workspaceLifecycleAppService,
                                      AgentStreamAppService streamAppService,
                                      @Value("${app.knowledge.top-k:5}") int injectTopK) {
        this.knowledgePort = knowledgePort;
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.streamAppService = streamAppService;
        this.injectTopK = injectTopK;
    }

    // ---------- 摄取（五类，A5 §1；调用点在各编排事务块之后） ----------

    /**
     * ARTIFACT：门 approve 后按主链定义读本阶段产物清单文件（v1 仅需求梳理段
     * docs/PRD.md，#41 起路径随主链产物单一事实）分块入库——source_ref =
     * {@code {projectId}:{stage}:{文件名}}，产物未产出（文件缺）记日志跳过。
     */
    void indexStageArtifacts(Long projectId, String stage) {
        quietly("ARTIFACT 摄取", () -> {
            Project project = projectOf(projectId);
            List<String> artifacts = ProjectMainChain.definition().find(stage)
                    .map(StageEntry::artifacts)
                    .orElse(null);
            if (artifacts == null || artifacts.isEmpty()) {
                return; // 无产物清单段（Demo/测试/验收）——无 ARTIFACT 素材
            }
            for (String fileName : artifacts) {
                String content = readWorkspaceFile(project.getWorkspaceId(), fileName);
                indexOne(project, KIND_ARTIFACT, projectId + ":" + stage + ":" + fileName,
                        fileName, chunkByParagraph(content), Map.of("stage", stage));
            }
        });
    }

    /**
     * QA：settle(Answer) 后即刻——问题取等待点 body 的 questions 文本（缺则退
     * summary），答复取 answers 选项 label，按问题顺序配对成单条
     * {@code 问：…\n答：…}（A5 §1 格式实现定）。
     */
    void indexQa(Long projectId, String waitId, Map<String, Object> body, String summary,
                 List<List<String>> answers) {
        quietly("QA 摄取", () -> {
            Project project = projectOf(projectId);
            List<String> questions = questionsOf(body, summary);
            if (questions.isEmpty()) {
                log.warn("等待点 {} 无可提取的问题文本，跳过 QA 入库", waitId);
                return;
            }
            StringBuilder qa = new StringBuilder();
            for (int i = 0; i < questions.size(); i++) {
                List<String> labels = answers != null && i < answers.size() ? answers.get(i) : List.of();
                qa.append("问：").append(questions.get(i)).append('\n')
                        .append("答：").append(String.join("、", labels)).append('\n');
            }
            indexOne(project, KIND_QA, waitId, truncate(String.join("；", questions), 100),
                    List.of(qa.toString().stripTrailing()), null);
        });
    }

    /**
     * FEEDBACK：confirmation 落库后——门 kind/决策/理由成形单条（approve 无
     * reason，理由行省略），source_ref = confirmationId。
     */
    void indexFeedback(Long projectId, Confirmation confirmation) {
        quietly("FEEDBACK 摄取", () -> {
            Project project = projectOf(projectId);
            String title = confirmation.getKind().getName() + "·" + confirmation.getDecision().getName();
            StringBuilder feedback = new StringBuilder("〔").append(title).append("〕");
            if (confirmation.getReason() != null && !confirmation.getReason().isBlank()) {
                feedback.append("理由：").append(confirmation.getReason());
            }
            indexOne(project, KIND_FEEDBACK, confirmation.getId().toString(),
                    truncate(title, TITLE_MAX_CHARS), List.of(feedback.toString()), null);
        });
    }

    /**
     * TEST_REPORT：任务 confirm 后读 submitted_payload.report 分块入库（首轮与
     * 复测同规则——幂等 by (taskId) 删后插天然覆盖），source_ref = taskId。
     * task BC 的 confirm 编排挂钩（A5 §8）。
     */
    public void indexTestReport(Long projectId, Long taskId, String taskTitle, String report) {
        quietly("TEST_REPORT 摄取", () -> {
            Project project = projectOf(projectId);
            String title = truncate("测试报告：" + (taskTitle == null ? "" : taskTitle), TITLE_MAX_CHARS);
            indexOne(project, KIND_TEST_REPORT, taskId.toString(), title,
                    chunkByParagraph(report), Map.of("taskId", taskId.toString()));
        });
    }

    /**
     * BUG：任务 confirm 的 Bug 入库处，仅 OPEN 时刻一次——一条一块（标题/描述/
     * 复现步骤/严重级），source_ref = bugId；状态演化/FIXED/VERIFIED 明确不入
     * （A5 §1）。task BC 的 confirm 编排挂钩（A5 §8）。
     */
    public void indexBug(Long projectId, Long bugId, String title, String description,
                         String reproSteps, String severityLabel) {
        quietly("BUG 摄取", () -> {
            Project project = projectOf(projectId);
            StringBuilder bug = new StringBuilder("【标题】").append(title);
            if (notBlank(description)) {
                bug.append("\n【描述】").append(description.strip());
            }
            if (notBlank(reproSteps)) {
                bug.append("\n【复现步骤】").append(reproSteps.strip());
            }
            if (notBlank(severityLabel)) {
                bug.append("\n【严重级】").append(severityLabel.strip());
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("bugId", bugId.toString());
            if (notBlank(severityLabel)) {
                meta.put("severity", severityLabel.strip());
            }
            indexOne(project, KIND_BUG, bugId.toString(), truncate(title, TITLE_MAX_CHARS),
                    List.of(bug.toString()), meta);
        });
    }

    /**
     * 检索（search 演示端点用，A5 §4）：全局跨项目纯相似——异常照抛（dev 视角），
     * topK 缺省取注入配置值；embedding 不可用由底座降级为空列表。
     */
    public List<KnowledgeHit> retrieve(String query, Integer topK) {
        return knowledgePort.retrieve(query, topK != null ? topK : injectTopK);
    }

    // ---------- 级联清理（A5 §5） ----------

    /** 项目 DELETE 级联清空 knw_chunks：失败记日志不阻断删除（残留可重删收敛）。 */
    void purgeByProject(Long projectId) {
        quietly("项目知识级联清理", () -> knowledgePort.purgeByProject(projectId.toString()));
    }

    // ---------- 检索注入（runTask 单点缝，A5 §3） ----------

    /**
     * run 下发前的单点注入：检索（query = prompt 全文截断、topK 可配）→ 命中
     * 发射 {@code knowledge-retrieved}（items）→ 命中内容前置注入 prompt。命中为
     * 空或任何失败：原 prompt 照发（空注入，不炸——runTask 主流程优先）。
     */
    public String injectForRun(Long projectId, String runId, String prompt) {
        try {
            List<KnowledgeHit> hits = knowledgePort.retrieve(truncate(prompt, MAX_QUERY_CHARS),
                    injectTopK);
            if (hits.isEmpty()) {
                return prompt;
            }
            emitKnowledgeRetrieved(projectId, runId, hits);
            return injectIntoPrompt(prompt, hits);
        } catch (RuntimeException e) {
            log.warn("[knowledge] 检索注入失败（降级为空注入，不阻断 run）：{}", e.toString());
            return prompt;
        }
    }

    /** 命中前置注入（格式：知识块清单在前、原任务在后，界标分节）。 */
    private static String injectIntoPrompt(String prompt, List<KnowledgeHit> hits) {
        StringBuilder injected = new StringBuilder("【平台知识库·相似历史沉淀】")
                .append("以下为跨项目检索到的历史素材（问答/产物/门决策/测试报告/Bug），供参考：");
        for (KnowledgeHit hit : hits) {
            injected.append("\n\n〔").append(hit.kind()).append("｜").append(hit.sourceProjectName())
                    .append("〕").append(hit.title()).append('\n').append(hit.chunk());
        }
        return injected.append("\n\n【本次任务】\n").append(prompt).toString();
    }

    /** SSE knowledge-retrieved（agent 流通道，A5 §4：命中可见）。 */
    private void emitKnowledgeRetrieved(Long projectId, String runId, List<KnowledgeHit> hits) {
        List<Map<String, Object>> items = hits.stream()
                .map(hit -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put(AgentEventTypes.KNOWLEDGE_KIND_FIELD, hit.kind());
                    item.put(AgentEventTypes.KNOWLEDGE_PROJECT_NAME_FIELD, hit.sourceProjectName());
                    item.put(AgentEventTypes.KNOWLEDGE_TITLE_FIELD, hit.title());
                    item.put(AgentEventTypes.KNOWLEDGE_SNIPPET_FIELD, hit.chunk());
                    return item;
                })
                .toList();
        streamAppService.publish(AgentEventTypes.KNOWLEDGE_RETRIEVED, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, runId,
                AgentEventTypes.KNOWLEDGE_ITEMS_FIELD, items));
        log.info("[knowledge] run {} 注入命中 {} 条（project {}）", runId, items.size(), projectId);
    }

    // ---------- 内部 ----------

    /** 单素材入库（幂等 by (kind, sourceRef) 删后插归底座）：无有效分块跳过。 */
    private void indexOne(Project project, String kind, String sourceRef, String title,
                          List<String> chunks, Map<String, Object> meta) {
        if (chunks == null || chunks.isEmpty()) {
            log.warn("素材无有效分块，跳过知识入库：kind={} sourceRef={}", kind, sourceRef);
            return;
        }
        knowledgePort.index(new KnowledgeSpec(kind, sourceRef, project.getId().toString(),
                project.getName(), title, chunks, meta));
        log.info("[knowledge] 素材入库：kind={} sourceRef={} 块数={}", kind, sourceRef, chunks.size());
    }

    /** 摄取降级包装（A5 §1：失败记日志跳过，不阻断调用方主流程）。 */
    private static void quietly(String what, Runnable ingestion) {
        try {
            ingestion.run();
        } catch (RuntimeException e) {
            log.warn("[knowledge] {} 失败（降级跳过）：{}", what, e.toString());
        }
    }

    /** 项目装载（并发删除等导致缺行时以 404 语义降级跳过摄取）。 */
    private Project projectOf(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }

    /** 工作区产物文件读取：exitCode 非 0（文件缺/容器已亡）返回 null 由调用方跳过。 */
    private String readWorkspaceFile(Long workspaceId, String fileName) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(workspaceId),
                new WorkspaceExecCommand("cat '" + WORKSPACE_ROOT + fileName + "'"));
        if (result.exitCode() != 0) {
            log.warn("工作区产物文件读取失败（未产出？），跳过：workspace={} file={} exit={} stderr={}",
                    workspaceId, fileName, result.exitCode(), result.stderr());
            return null;
        }
        return result.stdout();
    }

    /** 等待点问题文本提取：body.questions[].question（引擎载荷原样）→ 缺则退
     * summary 单问（适配器中性短文本）。 */
    private static List<String> questionsOf(Map<String, Object> body, String summary) {
        if (body != null && body.get("questions") instanceof List<?> questions) {
            List<String> texts = questions.stream()
                    .filter(Map.class::isInstance)
                    .map(question -> Objects.toString(((Map<?, ?>) question).get("question"), null))
                    .filter(ProjectKnowledgeAppService::notBlank)
                    .toList();
            if (!texts.isEmpty()) {
                return texts;
            }
        }
        return notBlank(summary) ? List.of(summary.strip()) : List.of();
    }

    /**
     * 段落级分块（A5 §1：~800 字符）：按空行切段落，相邻段落并入至目标长度；
     * 超长单段按目标长度硬切（保底不丢内容）。空白输入返回空清单（调用方跳过）。
     */
    static List<String> chunkByParagraph(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : text.strip().split("\\n\\s*\\n")) {
            String paragraph = raw.strip();
            while (paragraph.length() > CHUNK_TARGET_CHARS) { // 超长单段硬切
                flush(chunks, current);
                chunks.add(paragraph.substring(0, CHUNK_TARGET_CHARS));
                paragraph = paragraph.substring(CHUNK_TARGET_CHARS).stripLeading();
            }
            if (paragraph.isEmpty()) {
                continue;
            }
            if (!current.isEmpty()
                    && current.length() + paragraph.length() + 2 > CHUNK_TARGET_CHARS) {
                flush(chunks, current);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }
        flush(chunks, current);
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
