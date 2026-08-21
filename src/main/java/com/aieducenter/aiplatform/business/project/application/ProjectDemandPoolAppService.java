package com.aieducenter.aiplatform.business.project.application;

import java.util.List;

import org.springframework.stereotype.Service;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.project.application.dto.command.AddDemandEntryCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.DemandPoolEntryResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.DemandPoolEntry;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.DemandSource;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.DemandPoolEntryRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 需求池用例（片5c，A3 §4）：项目级收件清单——随时可记（验收前后、期开期关
 * 都能记：工具与过程正交），记录不等同开工；开新期时作为需求梳理输入。
 *
 * <p>入池是显式动作（驳回反馈不自动入池）；kind 不强分类、来源缺省用户。
 * append-only 无状态列，列表按记录时间新→旧。</p>
 */
@Service
public class ProjectDemandPoolAppService {

    private final ProjectRepository projectRepository;
    private final DemandPoolEntryRepository demandPoolEntryRepository;

    public ProjectDemandPoolAppService(ProjectRepository projectRepository,
                                       DemandPoolEntryRepository demandPoolEntryRepository) {
        this.projectRepository = projectRepository;
        this.demandPoolEntryRepository = demandPoolEntryRepository;
    }

    /**
     * 入池（一条收件记录）：内容必填（域不变量 + REST 校验双层），来源缺省用户，
     * 记录账号取会话上下文（无会话可空）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public DemandPoolEntryResponse add(Long projectId, AddDemandEntryCommand command) {
        requireProject(projectId);
        DemandPoolEntry entry = demandPoolEntryRepository.save(DemandPoolEntry.entryOf(
                projectId, command.content(), command.kind(),
                DemandSource.orDefault(command.source()), RequestContext.getUserId()));
        return toResponse(entry);
    }

    /**
     * 项目的收件清单（新→旧）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public List<DemandPoolEntryResponse> entries(Long projectId) {
        requireProject(projectId);
        return demandPoolEntryRepository
                .findByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
                .map(ProjectDemandPoolAppService::toResponse)
                .toList();
    }

    // ---------- 内部 ----------

    private static DemandPoolEntryResponse toResponse(DemandPoolEntry entry) {
        return new DemandPoolEntryResponse(
                entry.getId().toString(),
                entry.getContent(),
                entry.getKind(),
                entry.getKind() != null ? entry.getKind().getName() : null,
                entry.getSource(),
                entry.getSource().getName(),
                entry.getCreatedBy(),
                entry.getCreatedAt());
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
