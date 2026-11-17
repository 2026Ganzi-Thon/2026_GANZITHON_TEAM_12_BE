package com.comedown.experience.calendarflow.api;

import com.comedown.experience.calendarflow.application.CalendarSyncFlow;
import com.comedown.experience.suggestions.application.SuggestionMessageAi;
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
    private final SuggestionMessageAi messages;

    CalendarSyncController(CalendarSyncFlow flow, ServiceTime time, SuggestionMessageAi messages) {
        this.flow = flow;
        this.time = time;
        this.messages = messages;
    }

    @PostMapping("/api/calendar/sync")
    SyncResponse sync(CurrentUser user) {
        CalendarSyncFlow.Result r = flow.sync(user.id());
        messages.applyToProposed(user.id(), "PRE_START"); // 저장이 끝난 뒤 새 제안의 문구를 AI 문구로(실패해도 동기화는 성공)
        return new SyncResponse(time.toResponse(r.from()), time.toResponse(r.to()), r.fetched(), r.saved().kept(),
                r.saved().created(), r.saved().updated(), r.saved().deleted(), r.saved().classified());
    }

    record SyncResponse(OffsetDateTime from, OffsetDateTime to, int fetched, int kept, int created, int updated,
            int deleted, int classified) {
    }
}
