package com.comedown.recovery.domain;

import com.comedown.recovery.domain.policy.DailyRecoveryState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 일별 회복 상태(hrv_daily_states). 측정 또는 컨디션을 저장한 날마다 1행이며 계산 결과 캐시다.
 * 재측정·컨디션 변경·삭제 때 그 날짜부터 다시 계산해 새로 쓴다.
 */
@Entity
@Table(name = "hrv_daily_states")
@IdClass(HrvDailyState.Key.class)
public class HrvDailyState implements Persistable<HrvDailyState.Key> {

    @Id
    @Column(name = "user_id", nullable = false)
    private long userId;

    @Id
    @Column(name = "state_date", nullable = false)
    private LocalDate stateDate;

    @Column(name = "daily_ln_rmssd")
    private Double dailyLnRmssd;

    @Column(name = "daily_rmssd_ms")
    private Double dailyRmssdMs;

    @Column(name = "measurement_count", nullable = false)
    private int measurementCount;

    @Column(name = "quality_level", length = 12)
    private String qualityLevel;

    @Column(name = "confounded", nullable = false)
    private boolean confounded;

    @Column(name = "baseline_established", nullable = false)
    private boolean baselineEstablished;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "baseline_valid_days", nullable = false)
    private int baselineValidDays;

    @Column(name = "baseline_mean_ln")
    private Double baselineMeanLn;

    @Column(name = "baseline_sd_ln")
    private Double baselineSdLn;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "baseline_sample_count", nullable = false)
    private int baselineSampleCount;

    @Column(name = "baseline_frozen", nullable = false)
    private boolean baselineFrozen;

    @Column(name = "trend_ln")
    private Double trendLn;

    @Column(name = "change_pct", precision = 6, scale = 1)
    private BigDecimal changePct;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_status", nullable = false, length = 20)
    private RecoveryStatus recoveryStatus;

    @Column(name = "calculation_version", nullable = false, length = 30)
    private String calculationVersion;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Transient
    private boolean isNew;

    protected HrvDailyState() {
    }

    /** 계산 결과로 새 행을 만든다(저장 시 바로 INSERT). */
    public static HrvDailyState of(long userId, DailyRecoveryState s, Instant computedAt) {
        HrvDailyState row = new HrvDailyState();
        row.userId = userId;
        row.stateDate = s.date();
        row.dailyLnRmssd = s.dailyLnRmssd();
        row.dailyRmssdMs = s.dailyRmssdMs();
        row.measurementCount = s.measurementCount();
        row.qualityLevel = s.qualityLevel();
        row.confounded = s.confounded();
        row.baselineEstablished = s.baselineEstablished();
        row.baselineValidDays = s.baselineValidDays();
        row.baselineMeanLn = s.baselineMeanLn();
        row.baselineSdLn = s.baselineSdLn();
        row.baselineSampleCount = s.baselineSampleCount();
        row.baselineFrozen = s.baselineFrozen();
        row.trendLn = s.trendLn();
        row.changePct = s.changePct();
        row.recoveryStatus = s.recoveryStatus();
        row.calculationVersion = s.calculationVersion();
        row.computedAt = Objects.requireNonNull(computedAt, "computedAt");
        row.isNew = true;
        return row;
    }

    @Override
    public Key getId() {
        return new Key(userId, stateDate);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public long getUserId() {
        return userId;
    }

    public LocalDate getStateDate() {
        return stateDate;
    }

    public Double getDailyLnRmssd() {
        return dailyLnRmssd;
    }

    public Double getDailyRmssdMs() {
        return dailyRmssdMs;
    }

    public int getMeasurementCount() {
        return measurementCount;
    }

    public String getQualityLevel() {
        return qualityLevel;
    }

    public boolean isConfounded() {
        return confounded;
    }

    public boolean isBaselineEstablished() {
        return baselineEstablished;
    }

    public int getBaselineValidDays() {
        return baselineValidDays;
    }

    public Double getBaselineMeanLn() {
        return baselineMeanLn;
    }

    public Double getBaselineSdLn() {
        return baselineSdLn;
    }

    public int getBaselineSampleCount() {
        return baselineSampleCount;
    }

    public boolean isBaselineFrozen() {
        return baselineFrozen;
    }

    public Double getTrendLn() {
        return trendLn;
    }

    public BigDecimal getChangePct() {
        return changePct;
    }

    public RecoveryStatus getRecoveryStatus() {
        return recoveryStatus;
    }

    public String getCalculationVersion() {
        return calculationVersion;
    }

    /** 복합 PK(user_id, state_date). */
    public static class Key implements Serializable {

        private long userId;
        private LocalDate stateDate;

        protected Key() {
        }

        public Key(long userId, LocalDate stateDate) {
            this.userId = userId;
            this.stateDate = stateDate;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && userId == other.userId && Objects.equals(stateDate, other.stateDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, stateDate);
        }
    }
}
