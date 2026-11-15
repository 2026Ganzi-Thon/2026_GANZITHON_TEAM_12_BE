package com.comedown.schedule.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 임의 기간의 일정 요약. {@code eventCount}는 기간에 시작한 집계 대상 일정 수(종일 일정 포함).
 *
 * @param to 기간 끝 날짜(포함)
 */
public record PeriodSummary(
        LocalDate from,
        LocalDate to,
        BigDecimal totalHours,
        BigDecimal weekendHours,
        int deadlineCount,
        int lateNightCount,
        int restDays,
        int eventCount) {
}
