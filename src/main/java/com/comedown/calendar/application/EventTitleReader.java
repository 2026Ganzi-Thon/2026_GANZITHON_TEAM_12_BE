package com.comedown.calendar.application;

import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.calendar.contract.CalendarEventSource;
import com.comedown.calendar.contract.SyncedEvent;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.time.InstantRange;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 저장하지 않는 일정 제목을 구글에서 실시간으로 읽는다. 구글 호출이므로 DB 트랜잭션 밖에서 쓴다. */
@Component
class EventTitleReader {

    private static final Logger log = LoggerFactory.getLogger(EventTitleReader.class);

    private final CalendarCredentialProvider credentials;
    private final CalendarEventSource source;

    EventTitleReader(CalendarCredentialProvider credentials, CalendarEventSource source) {
        this.credentials = credentials;
        this.source = source;
    }

    /** 범위와 겹치는 일정의 google event id → 제목. 읽지 못하면 null(제목 없이 응답). */
    Map<String, String> read(long userId, InstantRange range) {
        try {
            Map<String, String> titles = new HashMap<>();
            for (SyncedEvent e : source.fetch(credentials.credentialFor(userId), range)) {
                titles.put(e.googleEventId(), e.title());
            }
            return titles;
        } catch (ApiException | ExternalServiceException e) {
            log.warn("일정 제목을 읽지 못했습니다: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
