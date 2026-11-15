package com.comedown.experience.calendarflow.api;

import com.comedown.experience.calendarflow.application.CalendarSyncFlow;
import com.comedown.shared.security.CurrentUser;
import com.comedown.shared.time.ServiceTime;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/calendar/sync} → 200 (HTTP 책임: experience.calendarflow). */
@RestController
class CalendarSyncController {

    private final CalendarSyncFlow flow;
    private final ServiceTime time;

    CalendarSyncController(CalendarSyncFlow flow, ServiceTime time) {
        this.flow = flow;
        this.time = time;
    }

    @PostMapping("/api/calendar/sync")
    SyncResponse sync(CurrentUser user) {
        CalendarSyncFlow.Result r = flow.sync(user.id());
        return new SyncResponse(time.toResponse(r.from()), time.toResponse(r.to()), r.fetched(), r.saved().kept(),
                r.saved().created(), r.saved().updated(), r.saved().deleted(), r.saved().classified());
    }

    record SyncResponse(OffsetDateTime from, OffsetDateTime to, int fetched, int kept, int created, int updated,
            int deleted, int classified) {
    }
}
