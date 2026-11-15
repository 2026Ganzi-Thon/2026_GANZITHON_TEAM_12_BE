package com.comedown.calendar.infrastructure.persistence;

import com.comedown.calendar.contract.CalendarEventReader;
import com.comedown.calendar.contract.CalendarEventSnapshot;
import com.comedown.shared.time.InstantRange;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
class JpaCalendarEventReader implements CalendarEventReader {

    private final CalendarEventJpaRepository repository;

    JpaCalendarEventReader(CalendarEventJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<CalendarEventSnapshot> findOverlapping(long userId, InstantRange range) {
        return repository.findOverlapping(userId, range.start(), range.endExclusive()).stream()
                .map(JpaCalendarEventReader::toSnapshot).toList();
    }

    private static CalendarEventSnapshot toSnapshot(CalendarEventEntity e) {
        // 종일 일정은 서울 날짜로 정규화해 함께 준다.
        LocalDate startDate = e.allDay() ? LocalDate.ofInstant(e.startAt(), ServiceTime.ZONE) : null;
        LocalDate endDateExclusive = e.allDay() ? LocalDate.ofInstant(e.endAt(), ServiceTime.ZONE) : null;
        return new CalendarEventSnapshot(e.startAt(), e.endAt(), startDate, endDateExclusive, e.allDay(),
                e.category(), Boolean.TRUE.equals(e.deadline()), e.createdByApp(),
                "transparent".equalsIgnoreCase(e.transparency()), "cancelled".equalsIgnoreCase(e.eventStatus()));
    }
}
