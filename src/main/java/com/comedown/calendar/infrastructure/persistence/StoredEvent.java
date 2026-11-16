package com.comedown.calendar.infrastructure.persistence;

import com.comedown.calendar.domain.CategorySource;
import com.comedown.calendar.domain.Classification;
import com.comedown.calendar.domain.EventCategory;
import com.comedown.calendar.domain.EventKeys;
import java.time.Instant;

/** calendar_events 한 행(제목 없음). */
public record StoredEvent(
        long id,
        String googleEventId,
        String recurringEventId,
        String titleHash,
        Instant startAt,
        Instant endAt,
        boolean allDay,
        String eventStatus,
        String transparency,
        boolean createdByApp,
        String category,
        String categorySource,
        Integer burden,
        Boolean adjustable,
        Boolean deadline,
        Instant googleUpdatedAt,
        String customCategory) {

    public EventKeys keys() {
        return new EventKeys(googleEventId, recurringEventId, titleHash);
    }

    /** 저장된 분류. 분류 전이면 null. */
    public Classification classification() {
        if (category == null) {
            return null;
        }
        return new Classification(EventCategory.valueOf(category), burden, adjustable, deadline,
                categorySource == null ? CategorySource.RULE : CategorySource.valueOf(categorySource),
                customCategory);
    }
}
