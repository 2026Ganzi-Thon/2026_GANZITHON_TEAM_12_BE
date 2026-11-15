package com.comedown.calendar.api;

import com.comedown.calendar.application.CalendarEventQueryService;
import com.comedown.calendar.infrastructure.persistence.StoredEvent;
import com.comedown.schedule.contract.WeekLoadView;
import com.comedown.shared.security.CurrentUser;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/calendar/events} — 캘린더 탭 일정 조회(HTTP 변환만). */
@RestController
class CalendarEventController {

    private final CalendarEventQueryService query;
    private final ServiceTime time;

    CalendarEventController(CalendarEventQueryService query, ServiceTime time) {
        this.query = query;
        this.time = time;
    }

    @GetMapping("/api/calendar/events")
    EventsResponse events(CurrentUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        CalendarEventQueryService.Result result = query.query(user.id(), from, to);
        return new EventsResponse(result.events().stream().map(this::toResponse).toList(), result.week());
    }

    private EventResponse toResponse(CalendarEventQueryService.EventView view) {
        StoredEvent e = view.event();
        return new EventResponse(e.id(), e.googleEventId(), e.recurringEventId() != null, view.startDate(),
                view.endDateExclusive(), time.toResponse(e.startAt()), time.toResponse(e.endAt()), e.allDay(),
                view.title(), view.title() == null, e.category(), e.categorySource(), e.burden(), e.adjustable(),
                Boolean.TRUE.equals(e.deadline()), e.createdByApp());
    }

    record EventsResponse(List<EventResponse> events, WeekLoadView week) {
    }

    record EventResponse(long id, String googleEventId, boolean recurring, LocalDate startDate,
            LocalDate endDateExclusive, OffsetDateTime start, OffsetDateTime end, boolean allDay, String title,
            boolean titleUnavailable, String category, String categorySource, Integer burden, Boolean adjustable,
            boolean isDeadline, boolean createdByApp) {
    }
}
