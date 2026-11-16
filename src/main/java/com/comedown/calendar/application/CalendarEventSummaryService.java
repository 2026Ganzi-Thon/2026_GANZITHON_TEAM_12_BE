package com.comedown.calendar.application;

import com.comedown.calendar.contract.CalendarEventSummaryQuery;
import com.comedown.calendar.infrastructure.persistence.CalendarEventStore;
import com.comedown.calendar.infrastructure.persistence.StoredEvent;
import com.comedown.shared.time.InstantRange;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 일정 id → 요약. 저장된 일정(제목 없음)에 구글에서 실시간으로 읽은 제목을 붙인다.
 * 구글은 대상 일정을 모두 덮는 한 범위로 한 번만 부르고, 읽지 못하면 제목만 null이다.
 */
@Service
class CalendarEventSummaryService implements CalendarEventSummaryQuery {

    private final CalendarEventStore store;
    private final EventTitleReader titles;

    CalendarEventSummaryService(CalendarEventStore store, EventTitleReader titles) {
        this.store = store;
        this.titles = titles;
    }

    @Override
    public Map<Long, EventSummary> findByIds(long userId, Collection<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        List<StoredEvent> stored = store.findByIds(userId, eventIds);
        if (stored.isEmpty()) {
            return Map.of();
        }
        Instant from = stored.stream().map(StoredEvent::startAt).min(Instant::compareTo).orElseThrow();
        Instant to = stored.stream().map(StoredEvent::endAt).max(Instant::compareTo).orElseThrow();
        // 시작과 끝이 같은 일정도 범위에 들도록 끝을 1초 넓힌다.
        Map<String, String> read = titles.read(userId, new InstantRange(from, to.plusSeconds(1)));
        Map<Long, EventSummary> result = new LinkedHashMap<>();
        for (StoredEvent e : stored) {
            result.put(e.id(), new EventSummary(e.id(), read == null ? null : read.get(e.googleEventId()),
                    e.startAt(), e.category()));
        }
        return result;
    }
}
