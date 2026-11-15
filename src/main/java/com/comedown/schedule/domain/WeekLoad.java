package com.comedown.schedule.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 한 주의 부하 계산 결과(weekly_loads 한 행의 값). */
public record WeekLoad(
        LocalDate weekStart,
        BigDecimal totalHours,
        BigDecimal loadScore,
        BigDecimal vsUsualPct,
        UnavailableReason vsUsualUnavailableReason,
        int deadlineCount,
        int lateNightCount,
        BigDecimal weekendHours,
        int restDays,
        RiskLevel riskLevel,
        List<RiskReason> riskReasons,
        String calculationVersion) {

    public enum UnavailableReason { ZERO_BASELINE, INSUFFICIENT_DATA }

    public enum RiskLevel { NONE, WATCH, RISK }

    public enum RiskReason { LOAD_HIGH, DEADLINES, NO_REST_DAY, LATE_NIGHT, LONG_STREAK }
}
