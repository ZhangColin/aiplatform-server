package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.project.application.ProjectKnowledgeAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.KnowledgeSearchItemResponse;

/**
 * 知识检索演示 / 调试端点（A5 §4，dev 视角）：验收 curl 复验命中用；将来管理
 * 后台知识库视图的消费缝。项目沉淀列表端点 v1 不做（无消费方，A5 §4）。
 */
@RestController
@RequestMapping("/api/knowledge")
@Validated
@Tag(name = "Knowledge", description = "知识库检索（演示/调试；沉淀随主链自动发生）")
public class KnowledgeSearchController {

    private final ProjectKnowledgeAppService knowledgeAppService;

    public KnowledgeSearchController(ProjectKnowledgeAppService knowledgeAppService) {
        this.knowledgeAppService = knowledgeAppService;
    }

    @GetMapping("/search")
    @Operation(summary = "知识语义检索",
            description = "全局跨项目纯相似 topK（不过滤、不配额，A5 §3）。kind = 素材类别"
                    + "（ARTIFACT/QA/FEEDBACK/TEST_REPORT/BUG）；projectName 为来源项目"
                    + "（跨项目命中是特性）。q 空白 400 KNW_002、topK 非正 400 KNW_003；"
                    + "embedding 服务不可用时返回空列表（降级不炸）")
    public ApiResponse<List<KnowledgeSearchItemResponse>> search(
            @RequestParam String q,
            @RequestParam(required = false) Integer topK) {
        return ApiResponse.ok(knowledgeAppService.retrieve(q, topK).stream()
                .map(KnowledgeSearchItemResponse::of)
                .toList());
    }
}
