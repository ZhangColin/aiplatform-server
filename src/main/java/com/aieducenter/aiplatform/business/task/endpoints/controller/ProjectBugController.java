package com.aieducenter.aiplatform.business.task.endpoints.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.task.application.TaskQueryAppService;
import com.aieducenter.aiplatform.business.task.application.dto.response.BugResponse;

/**
 * 项目 Bug REST 面（dev 面板，A4 §6）：清单（状态/fix_run_id/fix_note/
 * closed_reason 全带）。修复派发（dispatch-fixes）与 bogus 手工关闭（close）
 * 随 #27 修复编排链。
 */
@RestController
@RequestMapping("/api/projects/{id}/bugs")
@Validated
@Tag(name = "项目 Bug", description = "Bug 清单（business.task，dev 面板）")
public class ProjectBugController {

    private final TaskQueryAppService queryAppService;

    public ProjectBugController(TaskQueryAppService queryAppService) {
        this.queryAppService = queryAppService;
    }

    @GetMapping
    @Operation(summary = "项目 Bug 清单（新→旧）",
            description = "status：1=OPEN 待修复 2=FIXED 已修复 3=VERIFIED 复测通过"
                    + "（唯一关闭态；G3 谓词 open = status ≠ 3）。fix_run_id/fix_note 随 #27 "
                    + "修复链写入，closed_reason 随 #27 手工关闭")
    public ApiResponse<List<BugResponse>> list(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.bugs(id));
    }
}
