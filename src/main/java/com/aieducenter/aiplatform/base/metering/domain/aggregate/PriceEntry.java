package com.aieducenter.aiplatform.base.metering.domain.aggregate;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;
import com.cartisan.core.stereotype.Aggregate;

import com.aieducenter.aiplatform.base.metering.domain.error.MeteringMessage;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;

/**
 * 单价行（{@code met_price_entries}，A6 §1）：provider × model × 档位 × 币种 ×
 * 生效区间的每 token 单价——平台成本换算的匹配数据，base.metering 私有表（不经
 * 端口暴露，业务层零感知）。
 *
 * <p><b>改价 = 关旧行开新行</b>（append 式不 UPDATE 单价）：{@code unitPrice} 等
 * 匹配列只插入不写，唯 {@code effectiveTo} 可落（关行）；事件按 ts 落
 * {@code [effectiveFrom, effectiveTo)} 区间匹配单价，历史成本不漂移。生效区间
 * 不得重叠——唯一约束只防同起点，重叠是维护事故（换算重复计）。v1 维护入口 =
 * 手工 SQL + 启动种子（{@code PriceEntrySeeder}），维护 API 挂 fog「管理后台」。</p>
 */
@Entity
@Table(name = "met_price_entries")
@Aggregate
@Getter
public class PriceEntry extends Auditable implements AggregateRoot<PriceEntry, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "provider", nullable = false, updatable = false, length = 50)
    private String provider;

    @Column(name = "model", nullable = false, updatable = false, length = 100)
    private String model;

    @Column(name = "token_kind", nullable = false, updatable = false)
    private TokenKind tokenKind;

    @Column(name = "unit_price", nullable = false, updatable = false, precision = 20, scale = 10)
    private BigDecimal unitPrice;

    @Column(name = "currency", nullable = false, updatable = false, length = 10)
    private String currency;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    /** 生效终点（不含）；null = 当前行。整行唯一可写列（关行动作）。 */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    protected PriceEntry() {
    }

    private PriceEntry(String provider, String model, TokenKind tokenKind, BigDecimal unitPrice,
                       String currency, Instant effectiveFrom) {
        this.provider = provider;
        this.model = model;
        this.tokenKind = tokenKind;
        this.unitPrice = unitPrice;
        this.currency = currency;
        this.effectiveFrom = effectiveFrom;
    }

    /**
     * 开新行（生效区间从此刻/指定时点起，敞口）。
     */
    public static PriceEntry open(String provider, String model, TokenKind tokenKind,
                                  BigDecimal unitPrice, String currency, Instant effectiveFrom) {
        if (isBlank(provider) || isBlank(model) || tokenKind == null || unitPrice == null
                || unitPrice.signum() < 0 || isBlank(currency) || effectiveFrom == null) {
            throw new DomainException(MeteringMessage.PRICE_ENTRY_FIELDS_INCOMPLETE);
        }
        return new PriceEntry(provider, model, tokenKind, unitPrice, currency, effectiveFrom);
    }

    /**
     * 关旧行（改价上半步：落 effective_to；下半步 = {@link #open} 开新行）。
     */
    public void close(Instant effectiveTo) {
        if (effectiveTo == null || effectiveTo.isBefore(effectiveFrom)) {
            throw new DomainException(MeteringMessage.PRICE_ENTRY_CLOSE_INVALID);
        }
        this.effectiveTo = effectiveTo;
    }

    /**
     * 聚合 ID = 行 id。
     */
    @Override
    public Long getId() {
        return id;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
