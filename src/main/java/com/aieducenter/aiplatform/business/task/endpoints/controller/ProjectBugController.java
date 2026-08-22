package com.aieducenter.aiplatform.business.task.endpoints.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.task.application.FixDispatchAppService;
import com.aieducenter.aiplatform.business.task.application.TaskLifecycleAppService;
import com.aieducenter.aiplatform.business.task.application.TaskQueryAppService;
import com.aieducenter.aiplatform.business.task.application.dto.command.CloseBugCommand;
import com.aieducenter.aiplatform.business.task.application.dto.response.BugResponse;

/**
 * 项目 Bug REST 面（dev 面板，A4 §6）：清单（状态/fix_run_id/fix_note/
 * closed_reason 全带）+ 修复（重）派发 + bogus 手工关闭（#27 修复编排链）。
 */
@RestController
@RequestMapping("/api/projects/{id}/bugs")
@Validated
@Tag(name = "项目 Bug", description = "Bug 清单 / 修复派发 / 手工关闭（business.task，dev 面板）")
public class ProjectBugController {

    private final TaskQueryAppService queryAppService;
    private final TaskLifecycleAppService lifecycleAppService;
    private final FixDispatchAppService fixDispatchAppService;

    public ProjectBugController(TaskQueryAppService queryAppService,
                                TaskLifecycleAppService lifecycleAppService,
                                FixDispatchAppService fixDispatchAppService) {
        this.queryAppService = queryAppService;
        this.lifecycleAppService = lifecycleAppService;
        this.fixDispatchAppService = fixDispatchAppService;
    }

    @GetMapping
    @Operation(summary = "项目 Bug 清单（新→旧）",
            description = "status：1=OPEN 待修复 2=FIXED 已修复 3=VERIFIED 复测通过"
                    + "（唯一关闭态；G3 谓词 open = status ≠ 3）。fix_run_id/fix_note "
                    + "随修复链写入（in-flight = OPEN ∧ fix_run_id 非空），"
                    + "closed_reason 随手工关闭")
    public ApiResponse<List<BugResponse>> list(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.bugs(id));
    }

    @PostMapping("/dispatch-fixes")
    @Operation(summary = "修复（重）派发（幂等手动）",
            description = "只派 OPEN ∧ 无修复 run 引用的 Bug；已有修复 run 在飞则空转"
                    + "（同项目同时至多一个修复 run，逐 Bug 一 run 一新会话串行链）。"
                    + "确认时点自动触发（首轮入库/复测退回），本端点是手动兜底——"
                    + "失败回池的 Bug 由下次派发重试")
    public ApiResponse<Void> dispatchFixes(@PathVariable String id) {
        fixDispatchAppService.dispatchFixes(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{bugId}/close")
    @Operation(summary = "bogus Bug 手工关闭（reason 必填）",
            description = "OPEN/FIXED → VERIFIED + closed_reason——复测通过唯一关闭态的"
                    + "带理由别名动作，不加第四态（G3 谓词不变）；VERIFIED 终态再关 409 TASK_002。"
                    + "跨项目/不存在 404 TASK_005")
    public ApiResponse<BugResponse> close(@PathVariable String id,
                                          @PathVariable Long bugId,
                                          @Valid @RequestBody CloseBugCommand command) {
        return ApiResponse.ok(lifecycleAppService.closeBug(id, bugId, command.reason()));
    }
}
