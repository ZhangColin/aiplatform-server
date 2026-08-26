package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 知识沉淀编排（A5 票 #28 验收）：五类摄取的素材成形（A5 §1 表逐行——kind/
 * source_ref/chunk 形态与幂等键）、分块器（段落级 ~800 字符）、检索注入单点
 * （query 截断 / topK 配置 / 空命中与降级不注入）、级联清理与摄取降级不炸。
 * 端口 mock——入库/检索的 SQL 与降级语义见 KnowledgeAppServiceTest（#17）。
 */
@SpringBootTest
class ProjectKnowledgeAppServiceTest {

    @Autowired
    private ProjectKnowledgeAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private KnowledgePort knowledgePort;

    @MockitoBean
    private AgentStreamAppService streamAppService;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_iterations");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_workspace_prd_when_index_stage_artifacts_then_chunked_spec_indexed() {
        Project project = persistedProject(9400L);
        when(workspaceLifecycleAppService.exec(eq("9400"), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse(
                        "# 电商 PRD\n\n目标：做一个电商官网。\n\n购物车支持优惠券。", "", 0));

        appService.indexStageArtifacts(project.getId(), "BA");

        ArgumentCaptor<KnowledgeSpec> spec = ArgumentCaptor.forClass(KnowledgeSpec.class);
        verify(knowledgePort).index(spec.capture());
        assertThat(spec.getValue().kind()).isEqualTo(ProjectKnowledgeAppService.KIND_ARTIFACT);
        assertThat(spec.getValue().sourceRef())
                .isEqualTo(project.getId() + ":BA:" + ProjectMainChain.PRD_ARTIFACT);
        assertThat(spec.getValue().projectId()).isEqualTo(project.getId().toString());
        assertThat(spec.getValue().projectName()).isEqualTo("知识测试");
        assertThat(spec.getValue().title()).isEqualTo(ProjectMainChain.PRD_ARTIFACT);
        // 段落级分块：短段落并入同一块（目标 ~800 字符内不拆散）
        assertThat(spec.getValue().chunks()).singleElement().isEqualTo(
                "# 电商 PRD\n\n目标：做一个电商官网。\n\n购物车支持优惠券。");
        assertThat(spec.getValue().meta()).containsEntry("stage", "BA");
        // 工作区读取地址：产物文件在 /workspace/docs/ 下（#41 主链产物单一事实）
        verify(workspaceLifecycleAppService).exec(eq("9400"),
                eq(new WorkspaceExecCommand("cat '/workspace/" + ProjectMainChain.PRD_ARTIFACT + "'")));
    }

    @Test
    void given_stage_without_artifacts_when_index_stage_artifacts_then_no_index() {
        Project project = persistedProject(9401L);

        appService.indexStageArtifacts(project.getId(), "DEMO"); // Demo 段产物清单留空（A3/A5）

        verifyNoInteractions(knowledgePort, workspaceLifecycleAppService);
    }

    @Test
    void given_question_body_when_index_qa_then_paired_single_chunk() {
        Project project = persistedProject(9402L);

        appService.indexQa(project.getId(), "wait-1",
                Map.of("questions", List.of(
                        Map.of("question", "用哪个前端框架?"),
                        Map.of("question", "要支持移动端吗?"))),
                "用哪个前端框架?",
                List.of(List.of("React"), List.of("要", "响应式")));

        ArgumentCaptor<KnowledgeSpec> spec = ArgumentCaptor.forClass(KnowledgeSpec.class);
        verify(knowledgePort).index(spec.capture());
        assertThat(spec.getValue().kind()).isEqualTo(ProjectKnowledgeAppService.KIND_QA);
        assertThat(spec.getValue().sourceRef()).isEqualTo("wait-1");
        assertThat(spec.getValue().title()).isEqualTo("用哪个前端框架?；要支持移动端吗?");
        assertThat(spec.getValue().chunks()).singleElement().asString().isEqualTo(
                "问：用哪个前端框架?\n答：React\n问：要支持移动端吗?\n答：要、响应式");
    }

    @Test
    void given_body_without_questions_when_index_qa_then_summary_fallback() {
        Project project = persistedProject(9403L);

        // 引擎载荷无可提取问题文本（如 dsh 形态）：退适配器中性短文本单问
        appService.indexQa(project.getId(), "wait-2", Map.of(), "要支持暗色模式?",
                List.of(List.of("要")));

        ArgumentCaptor<KnowledgeSpec> spec = ArgumentCaptor.forClass(KnowledgeSpec.class);
        verify(knowledgePort).index(spec.capture());
        assertThat(spec.getValue().title()).isEqualTo("要支持暗色模式?");
        assertThat(spec.getValue().chunks()).singleElement()
                .isEqualTo("问：要支持暗色模式?\n答：要");
    }

    @Test
    void given_no_question_text_at_all_when_index_qa_then_skipped() {
        Project project = persistedProject(9404L);

        appService.indexQa(project.getId(), "wait-3", null, " ", List.of(List.of("x")));

        verifyNoInteractions(knowledgePort);
    }

    @Test
    void given_missing_project_when_index_then_degraded_no_throw() {
        // 并发删除等导致项目缺行：摄取降级跳过（不炸调用方主流程）
        appService.indexQa(-1L, "wait-x", Map.of(), "问题?", List.of(List.of("答")));
        appService.indexStageArtifacts(-1L, "BA");

        verifyNoInteractions(knowledgePort);
    }

    @Test
    void given_port_failure_when_index_then_degraded_no_throw() {
        Project project = persistedProject(9405L);
        org.mockito.Mockito.doThrow(new RuntimeException("向量库写失败"))
                .when(knowledgePort).index(any());

        appService.indexQa(project.getId(), "wait-4", Map.of(), "问题?", List.of(List.of("答")));

        // 底座真实错误也由编排挂钩降级兜住（A5 §1：不阻断主流程）
        verify(knowledgePort).index(any(KnowledgeSpec.class));
    }

    @Test
    void given_long_text_when_chunk_then_paragraph_level_with_hard_split() {
        String paragraph800 = "甲".repeat(800);
        String shortTail = "结尾段";
        String longParagraph = "乙".repeat(2000);

        List<String> chunks = ProjectKnowledgeAppService.chunkByParagraph(
                paragraph800 + "\n\n" + shortTail + "\n\n" + longParagraph);

        // 800 段独立成块（并入短尾会超目标长度）；超长段按 800 硬切三块（800/800/400）
        assertThat(chunks).hasSize(5);
        assertThat(chunks.get(0)).hasSize(800);
        assertThat(chunks.get(1)).isEqualTo(shortTail);
        assertThat(chunks.get(2)).hasSize(800);
        assertThat(chunks.get(3)).hasSize(800);
        assertThat(chunks.get(4)).hasSize(400);
        assertThat(ProjectKnowledgeAppService.chunkByParagraph("  ")).isEmpty();
        assertThat(ProjectKnowledgeAppService.chunkByParagraph(null)).isEmpty();
    }

    @Test
    void given_retrieve_when_search_then_passthrough_with_default_topk() {
        when(knowledgePort.retrieve("电商", 5)).thenReturn(List.of(
                new KnowledgeHit("QA", "第一单", "用哪个框架?", "问：…\n答：…")));

        List<KnowledgeHit> hits = appService.retrieve("电商", null);

        assertThat(hits).hasSize(1);
        verify(knowledgePort).retrieve("电商", 5); // topK 缺省取注入配置值
    }

    @Test
    void given_overlong_prompt_when_inject_for_run_then_query_truncated_and_prompt_injected() {
        Project project = persistedProject(9406L);
        String longPrompt = "需求".repeat(3000); // 6000 字 > 上限 2000
        when(knowledgePort.retrieve("需求".repeat(1000), 5)).thenReturn(List.of(
                new KnowledgeHit("ARTIFACT", "第一单", "PRD.md", "做一个电商官网")));

        String effective = appService.injectForRun(project.getId(), "run-inj", longPrompt);

        // query 截断到上限；命中前置注入、原文收尾
        verify(knowledgePort).retrieve(eq("需求".repeat(1000)), eq(5));
        assertThat(effective)
                .startsWith("【平台知识库·相似历史沉淀】")
                .contains("〔ARTIFACT｜第一单〕PRD.md")
                .endsWith("【本次任务】\n" + longPrompt);
        // SSE knowledge-retrieved：items 单条四键齐
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(streamAppService).publish(eq(AgentEventTypes.KNOWLEDGE_RETRIEVED),
                payload.capture());
        assertThat(payload.getValue())
                .containsEntry("projectId", project.getId().toString())
                .containsEntry("runId", "run-inj");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) payload.getValue().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0))
                .containsEntry("kind", "ARTIFACT")
                .containsEntry("projectName", "第一单")
                .containsEntry("title", "PRD.md")
                .containsEntry("snippet", "做一个电商官网");
    }

    @Test
    void given_retrieve_failure_when_inject_for_run_then_original_prompt_no_sse() {
        Project project = persistedProject(9407L);
        when(knowledgePort.retrieve(anyString(), eq(5)))
                .thenThrow(new RuntimeException("embedding 服务不可用"));

        String effective = appService.injectForRun(project.getId(), "run-deg", "原任务");

        // 检索降级为空注入（A5 §3）：原 prompt 照发、不发 SSE
        assertThat(effective).isEqualTo("原任务");
        verify(streamAppService, never()).publish(anyString(), any());
    }

    @Test
    void given_purge_when_delete_cascade_then_port_called_and_failure_degraded() {
        Project project = persistedProject(9408L);
        org.mockito.Mockito.doThrow(new RuntimeException("清理失败"))
                .when(knowledgePort).purgeByProject(anyString());

        appService.purgeByProject(project.getId()); // 不抛（残留可重删收敛）
        verify(knowledgePort).purgeByProject(project.getId().toString());
    }

    // ---------- 测试数据 ----------

    private Project persistedProject(long workspaceId) {
        return projectRepository.save(Project.create("知识测试", ProjectType.WEBSITE,
                "opencode", workspaceId, 9500L));
    }
}
