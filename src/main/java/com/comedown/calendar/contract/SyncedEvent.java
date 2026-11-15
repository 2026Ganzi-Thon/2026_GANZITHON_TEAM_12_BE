package com.comedown.calendar.contract;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Google에서 읽은 일정(동기화 입력). 제목은 {@code title_hash} 계산에만 쓰고 저장하지 않는다.
 * 종일 일정은 {@code startDate}/{@code endDateExclusive}를, 시간 일정은 {@code start}/{@code end}를 채운다.
 */
public record SyncedEvent(
        String googleEventId,
        String recurringEventId,
        String title,
        boolean allDay,
        Instant start,
        Instant end,
        LocalDate startDate,
        LocalDate endDateExclusive,
        String status,
        String transparency,
        boolean createdByApp,
        Instant googleUpdatedAt) {

    @Override
    public String toString() {
        return "SyncedEvent[" + googleEventId + "]";
    }
}
