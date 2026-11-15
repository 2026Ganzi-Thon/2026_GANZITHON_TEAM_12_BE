package com.comedown.integration.google;

import com.comedown.account.contract.CalendarCredential;
import com.comedown.calendar.contract.CalendarEventWriter;
import org.springframework.stereotype.Component;

/** calendar의 {@link CalendarEventWriter} 포트를 Google Calendar API로 구현한다. 제품 규칙은 갖지 않는다. */
@Component
class GoogleCalendarEventWriter implements CalendarEventWriter {

    private final GoogleCalendarClient client;

    GoogleCalendarEventWriter(GoogleCalendarClient client) {
        this.client = client;
    }

    @Override
    public String register(CalendarCredential credential, NewEvent event) {
        boolean created = client.insert(credential.accessToken(), event.registrationKey(), event.title(),
                event.start(), event.end());
        if (!created && client.get(credential.accessToken(), event.registrationKey()).isEmpty()) {
            // 같은 id가 이미 쓰였지만 살아 있는 일정이 없다(삭제된 id는 다시 쓸 수 없다).
            throw new com.comedown.shared.error.ExternalServiceException(
                    com.comedown.shared.error.ExternalServiceException.Kind.FAILURE,
                    "등록 키가 이미 사용된 삭제된 일정입니다.");
        }
        return event.registrationKey();
    }

    @Override
    public boolean exists(CalendarCredential credential, String googleEventId) {
        return client.get(credential.accessToken(), googleEventId).isPresent();
    }

    @Override
    public DeleteResult deleteAppEvent(CalendarCredential credential, String googleEventId) {
        var event = client.get(credential.accessToken(), googleEventId);
        if (event.isEmpty()) {
            return DeleteResult.ALREADY_GONE;
        }
        if (!event.get().createdByApp()) {
            return DeleteResult.NOT_APP_OWNED;
        }
        client.delete(credential.accessToken(), googleEventId);
        return DeleteResult.DELETED;
    }
}
