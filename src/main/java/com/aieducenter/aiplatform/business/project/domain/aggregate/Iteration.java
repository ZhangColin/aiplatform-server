package com.aieducenter.aiplatform.business.project.domain.aggregate;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;

/**
 * 期聚合根（{@code prj_iterations}，A3 §2.1）：主链状态机主体——阶段名 + 阶段任务
 * 计数 + OPEN/CLOSED 挂在期上（每项目至多一个 OPEN，库侧部分唯一索引兜底）。
 *
 * <p>阶段名存主链定义的稳定键（{@link ProjectMainChain}，base.process
 * StageEntry.name）；推进/驳回停留/门禁由 base.process 引擎按定义裁决（片5b
 * 门操作接线），期只持有并保存状态。v1 每项目 1 期（seq=1，建项目即建）。</p>
 */
@Entity
@Table(name = "prj_iterations")
@Aggregate
@Getter
public class Iteration extends Auditable implements AggregateRoot<Iteration, Long> {

    /** v1 固定首期序号（「开二期」动作不建，A3 §7）。 */
    public static final int FIRST_SEQ = 1;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    @Column(name = "stage", nullable = false, length = 20)
    private String stage;

    @Column(name = "stage_task_count", nullable = false)
    private int stageTaskCount;

    @Column(name = "status", nullable = false)
    private IterationStatus status;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    protected Iteration() {
    }

    private Iteration(Long projectId, int seq, String stage) {
        if (projectId == null) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        if (stage == null || stage.isBlank()) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        this.projectId = projectId;
        this.seq = seq;
        this.stage = stage;
        this.stageTaskCount = 0;
        this.status = IterationStatus.OPEN;
    }

    /**
     * 开期（建项目事务内调用：seq=1、起始阶段 BA、OPEN、计数 0）。
     */
    public static Iteration open(Long projectId, int seq, String stage) {
        return new Iteration(projectId, seq, stage);
    }

    /**
     * 项目的当前期（A3 §2.1/§5 的 v1 寻址规则，列表/详情共用一处）：OPEN 优先；
     * 无 OPEN（已收口）回溯最新一期——期位置 stage=CLOSED 的展示来源；无期返回空。
     */
    public static Optional<Iteration> currentOf(List<Iteration> iterations) {
        if (iterations == null || iterations.isEmpty()) {
            return Optional.empty();
        }
        return iterations.stream()
                .filter(iteration -> iteration.status == IterationStatus.OPEN)
                .findFirst()
                .or(() -> iterations.stream()
                        .max(Comparator.comparingInt(Iteration::getSeq)));
    }

    /**
     * 记一次阶段任务（run 被引擎接受即计数——门禁输入，demo「没启动任务不能审批」
     * 语义）。期已收口不计数（工具与过程正交：期后任务照常跑，只是不再进过程计数）。
     */
    public void recordStageTask() {
        if (status != IterationStatus.OPEN) {
            return;
        }
        stageTaskCount++;
    }

    /**
     * 门通过推进（引擎 advance 裁决后调用）：迁入下一阶段、计数归零（下一阶段
     * 门禁从 0 起算）。期已收口即拒绝（收口后无过程迁移，A3 §2.2）。
     */
    public void advanceTo(String nextStage) {
        requireOpen();
        if (nextStage == null || nextStage.isBlank()) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        this.stage = nextStage;
        this.stageTaskCount = 0;
    }

    /**
     * 收口（验收门 G4 通过即联动，A3 §2.2 无交付段）：终态 + CLOSED + closedAt。
     * 「项目已交付」= 无 OPEN 期的派生投影，不另落字段。
     */
    public void close(String terminalStage) {
        requireOpen();
        if (terminalStage == null || terminalStage.isBlank()) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        this.stage = terminalStage;
        this.status = IterationStatus.CLOSED;
        this.closedAt = LocalDateTime.now();
    }

    private void requireOpen() {
        if (status != IterationStatus.OPEN) {
            throw new DomainException(ProjectMessage.ITERATION_NOT_OPEN);
        }
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
