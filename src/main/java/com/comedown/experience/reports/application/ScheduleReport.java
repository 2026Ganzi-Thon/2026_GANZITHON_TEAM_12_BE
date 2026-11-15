package com.comedown.experience.reports.application;

import com.comedown.schedule.contract.WeekLoadView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** {@code GET /api/schedule/report} 응답(A5 일정 리포트). */
public record ScheduleReport(
        OffsetDateTime syncedAt,
        Classification classification,
        PastMonth pastMonth,
        ThisWeek thisWeek,
        List<WeekLoadView> upcomingWeeks,
        boolean enoughData) {

    public record Classification(int total, int classified, int pending) {
    }

    public record PastMonth(LocalDate from, LocalDate to, int restDays, int lateNightCount, BigDecimal totalHours,
            BigDecimal weekendHours, int deadlineCount) {
    }

    public record ThisWeek(LocalDate weekStart, BigDecimal vsUsualPct, String riskLevel) {
    }
}
