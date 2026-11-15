package com.comedown.calendar.application;

import com.comedown.calendar.contract.CalendarDeadlineQuery;
import com.comedown.calendar.infrastructure.persistence.CalendarEventStore;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CalendarDeadlineService implements CalendarDeadlineQuery {

    private final CalendarEventStore store;

    CalendarDeadlineService(CalendarEventStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Deadline> upcomingAssignments(long userId, Instant from, Instant to) {
        return store.findAssignments(userId, from, to).stream()
                .map(e -> new Deadline(e.id(), e.startAt())).toList();
    }
}
