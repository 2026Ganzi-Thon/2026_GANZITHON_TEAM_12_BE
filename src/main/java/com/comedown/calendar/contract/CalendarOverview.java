package com.comedown.calendar.contract;

import java.time.Instant;

/**
 * 동기화한 일정 요약. {@code syncedAt}은 저장된(취소 제외) 일정이 마지막으로 동기화된 시각이고 저장된 일정이 없으면
 * null이다. 일정이 0건인 동기화는 일정 행을 남기지 않으므로 이 값만으로는 "동기화한 적 없음"과 구분할 수 없다.
 * {@code classified}는 분류가 있는 일정 수, {@code total}은 취소되지 않은 전체 일정 수.
 */
public record CalendarOverview(Instant syncedAt, int total, int classified) {

    public int pending() {
        return total - classified;
    }
}
