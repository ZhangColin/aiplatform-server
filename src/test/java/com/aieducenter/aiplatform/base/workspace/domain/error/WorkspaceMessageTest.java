package com.aieducenter.aiplatform.base.workspace.domain.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码契约：WSP_ 前缀注册（ADR-0001）+ HTTP 语义状态 + 文案非空。
 */
class WorkspaceMessageTest {

    @Test
    void given_workspace_messages_when_inspect_then_codes_prefixed_and_statuses_aligned() {
        assertThat(WorkspaceMessage.WORKSPACE_NOT_FOUND.code()).isEqualTo("WSP_001");
        assertThat(WorkspaceMessage.WORKSPACE_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.code()).isEqualTo("WSP_002");
        assertThat(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.httpStatus()).isEqualTo(500);
        assertThat(WorkspaceMessage.PORT_ALLOCATION_FAILED.code()).isEqualTo("WSP_003");
        assertThat(WorkspaceMessage.PORT_ALLOCATION_FAILED.httpStatus()).isEqualTo(500);
        assertThat(WorkspaceMessage.WORKSPACE_ID_INVALID.code()).isEqualTo("WSP_004");
        assertThat(WorkspaceMessage.WORKSPACE_ID_INVALID.httpStatus()).isEqualTo(400);
        assertThat(WorkspaceMessage.WORKSPACE_FIELDS_INCOMPLETE.code()).isEqualTo("WSP_005");
        assertThat(WorkspaceMessage.WORKSPACE_FIELDS_INCOMPLETE.httpStatus()).isEqualTo(400);
        assertThat(WorkspaceMessage.RESOURCE_FIELDS_INCOMPLETE.code()).isEqualTo("WSP_006");
        assertThat(WorkspaceMessage.RESOURCE_FIELDS_INCOMPLETE.httpStatus()).isEqualTo(400);
        assertThat(WorkspaceMessage.ENVIRONMENT_KIND_NOT_SUPPORTED.code()).isEqualTo("WSP_007");
        assertThat(WorkspaceMessage.ENVIRONMENT_KIND_NOT_SUPPORTED.httpStatus()).isEqualTo(400);
    }

    @Test
    void given_workspace_messages_when_inspect_then_messages_not_blank() {
        for (WorkspaceMessage message : WorkspaceMessage.values()) {
            assertThat(message.message()).isNotBlank();
        }
    }
}
