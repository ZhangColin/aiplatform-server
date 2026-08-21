package com.aieducenter.aiplatform.business.identity.domain.aggregate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.business.identity.domain.error.IdentityMessage;

/**
 * 账号聚合根（{@code idn_accounts}，A2 §3 最小版）。
 *
 * <p>首次登录按外部 ID（OIDC {@code sub}）自动建档，无 issuer 列（v1 单 IdP）、
 * 无角色列（角色票再加）。不软删除（Auditable 只取审计字段）。外部 ID 唯一索引
 * 是「同 sub 不重复建档」的最终防线，常态路径靠 callback 的 upsert 编排。</p>
 */
@Entity
@Table(name = "idn_accounts")
@Aggregate
@Getter
public class Account extends Auditable implements AggregateRoot<Account, Long> {

    /** 与库列宽对齐（V5 迁移） */
    public static final int EXTERNAL_ID_MAX_LENGTH = 100;
    public static final int DISPLAY_NAME_MAX_LENGTH = 200;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "external_id", nullable = false, updatable = false, length = EXTERNAL_ID_MAX_LENGTH)
    private String externalId;

    @Column(name = "display_name", nullable = false, length = DISPLAY_NAME_MAX_LENGTH)
    private String displayName;

    protected Account() {
    }

    private Account(String externalId, String displayName) {
        if (externalId == null || externalId.isBlank()
                || externalId.length() > EXTERNAL_ID_MAX_LENGTH
                || displayName == null || displayName.isBlank()
                || displayName.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new DomainException(IdentityMessage.ACCOUNT_FIELDS_INCOMPLETE);
        }
        this.id = TsidGenerator.newInstance().generate();
        this.externalId = externalId;
        this.displayName = displayName;
    }

    /**
     * 首次登录建档（callback 换 token + 验签成功后调用）。
     */
    public static Account register(String externalId, String displayName) {
        return new Account(externalId, displayName);
    }

    /**
     * 二次登录同步显示名（有变则更新）；返回是否发生变化，供调用方决定是否落库。
     */
    public boolean syncDisplayName(String candidate) {
        if (candidate == null || candidate.isBlank()
                || candidate.equals(this.displayName)
                || candidate.length() > DISPLAY_NAME_MAX_LENGTH) {
            return false;
        }
        this.displayName = candidate;
        return true;
    }
}
