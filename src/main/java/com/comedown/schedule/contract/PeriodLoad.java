package com.comedown.schedule.contract;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 기간 일정 요약(일정 리포트용 읽기 모델). {@code to}는 포함. */
public record PeriodLoad(
        LocalDate from,
        LocalDate to,
        int restDays,
        int lateNightCount,
        BigDecimal totalHours,
        BigDecimal weekendHours,
        int deadlineCount,
        int eventCount) {
}
