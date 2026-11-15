package com.comedown.calendar.contract;

import java.time.Instant;

/**
 * 동기화한 일정 요약. {@code syncedAt}은 마지막 동기화 시각(한 번도 없으면 null),
 * {@code classified}는 분류가 있는 일정 수, {@code total}은 취소되지 않은 전체 일정 수.
 */
public record CalendarOverview(Instant syncedAt, int total, int classified) {

    public int pending() {
        return total - classified;
    }
}
