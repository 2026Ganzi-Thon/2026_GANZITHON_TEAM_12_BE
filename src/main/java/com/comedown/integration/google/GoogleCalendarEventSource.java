package com.comedown.integration.google;

import com.comedown.account.contract.CalendarCredential;
import com.comedown.calendar.contract.CalendarEventSource;
import com.comedown.calendar.contract.SyncedEvent;
import com.comedown.shared.time.InstantRange;
import java.util.List;
import org.springframework.stereotype.Component;

/** calendar의 {@link CalendarEventSource} 포트를 Google Calendar API로 구현한다. 제품 규칙은 갖지 않는다. */
@Component
class GoogleCalendarEventSource implements CalendarEventSource {

    private final GoogleCalendarClient client;

    GoogleCalendarEventSource(GoogleCalendarClient client) {
        this.client = client;
    }

    @Override
    public List<SyncedEvent> fetch(CalendarCredential credential, InstantRange range) {
        return client.listEvents(credential.accessToken(), range.start(), range.endExclusive()).stream()
                .map(e -> new SyncedEvent(e.id(), e.recurringEventId(), e.title(), e.allDay(), e.start(), e.end(),
                        e.startDate(), e.endDateExclusive(), e.status(), e.transparency(), e.createdByApp(),
                        e.updated()))
                .toList();
    }
}
