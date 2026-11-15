package com.comedown.calendar.application;

import com.comedown.calendar.contract.CalendarDataDeletion;
import com.comedown.calendar.infrastructure.persistence.CalendarEventStore;
import com.comedown.calendar.infrastructure.persistence.CategoryOverrideStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CalendarDataDeletionService implements CalendarDataDeletion {

    private final CalendarEventStore events;
    private final CategoryOverrideStore overrides;

    CalendarDataDeletionService(CalendarEventStore events, CategoryOverrideStore overrides) {
        this.events = events;
        this.overrides = overrides;
    }

    @Override
    @Transactional
    public int deleteAll(long userId) {
        overrides.deleteAll(userId);
        return events.deleteAll(userId);
    }
}
