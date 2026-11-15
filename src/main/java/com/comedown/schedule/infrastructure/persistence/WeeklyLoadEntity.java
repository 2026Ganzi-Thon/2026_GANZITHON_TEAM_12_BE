package com.comedown.schedule.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** weekly_loads 매핑. schedule 내부에서만 쓰고 밖으로 내보내지 않는다. */
@Entity
@Table(name = "weekly_loads")
public class WeeklyLoadEntity {

    @EmbeddedId
    private WeeklyLoadId id;

    @Column(name = "total_hours")
    private BigDecimal totalHours;

    @Column(name = "load_score")
    private BigDecimal loadScore;

    @Column(name = "vs_usual_pct")
    private BigDecimal vsUsualPct;

    @Column(name = "vs_usual_unavailable_reason")
    private String vsUsualUnavailableReason;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "deadline_count")
    private int deadlineCount;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "late_night_count")
    private int lateNightCount;

    @Column(name = "weekend_hours")
    private BigDecimal weekendHours;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "rest_days")
    private int restDays;

    @Column(name = "risk_level")
    private String riskLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_reasons")
    private List<String> riskReasons;

    @Column(name = "calculation_version")
    private String calculationVersion;

    @Column(name = "computed_at")
    private Instant computedAt;

    protected WeeklyLoadEntity() {
    }

    WeeklyLoadEntity(WeeklyLoadId id) {
        this.id = id;
    }

    WeeklyLoadId id() { return id; }
    BigDecimal totalHours() { return totalHours; }
    BigDecimal loadScore() { return loadScore; }
    BigDecimal vsUsualPct() { return vsUsualPct; }
    String vsUsualUnavailableReason() { return vsUsualUnavailableReason; }
    int deadlineCount() { return deadlineCount; }
    int lateNightCount() { return lateNightCount; }
    BigDecimal weekendHours() { return weekendHours; }
    int restDays() { return restDays; }
    String riskLevel() { return riskLevel; }
    List<String> riskReasons() { return riskReasons; }
    String calculationVersion() { return calculationVersion; }

    @SuppressWarnings("checkstyle:ParameterNumber")
    void update(BigDecimal totalHours, BigDecimal loadScore, BigDecimal vsUsualPct, String unavailableReason,
            int deadlineCount, int lateNightCount, BigDecimal weekendHours, int restDays, String riskLevel,
            List<String> riskReasons, String calculationVersion, Instant computedAt) {
        this.totalHours = totalHours;
        this.loadScore = loadScore;
        this.vsUsualPct = vsUsualPct;
        this.vsUsualUnavailableReason = unavailableReason;
        this.deadlineCount = deadlineCount;
        this.lateNightCount = lateNightCount;
        this.weekendHours = weekendHours;
        this.restDays = restDays;
        this.riskLevel = riskLevel;
        this.riskReasons = riskReasons;
        this.calculationVersion = calculationVersion;
        this.computedAt = computedAt;
    }
}
