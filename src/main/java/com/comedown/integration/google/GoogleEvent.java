package com.comedown.integration.google;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Google Calendar 일정 한 건(필요한 필드만). 종일 일정은 {@code startDate}/{@code endDateExclusive}를 쓰고
 * 시간 일정은 {@code start}/{@code end}를 쓴다.
 *
 * @param createdByApp Come-down이 넣은 일정 표식({@code extendedProperties.private.comedownApp})이 있는지
 */
public record GoogleEvent(
        String id,
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
        Instant updated) {

    public boolean cancelled() {
        return "cancelled".equalsIgnoreCase(status);
    }
}
