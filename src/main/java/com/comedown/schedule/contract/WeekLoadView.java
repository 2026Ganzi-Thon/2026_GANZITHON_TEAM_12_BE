package com.comedown.schedule.contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** API의 WeekLoad 모양 그대로의 주간 부하 읽기 모델(다른 기능이 응답에 싣는다). */
public record WeekLoadView(
        LocalDate weekStart,
        LocalDate weekEnd,
        BigDecimal totalHours,
        BigDecimal loadScore,
        BigDecimal vsUsualPct,
        int deadlineCount,
        int lateNightCount,
        int restDays,
        String riskLevel,
        List<String> riskReasons,
        String vsUsualUnavailableReason) {
}
