package com.comedown.calendar.application;

import com.comedown.calendar.contract.CalendarEventSync;
import com.comedown.calendar.contract.SyncResult;
import com.comedown.calendar.contract.SyncedEvent;
import com.comedown.calendar.domain.CategoryOverride;
import com.comedown.calendar.domain.CategorySource;
import com.comedown.calendar.domain.Classification;
import com.comedown.calendar.domain.ClassificationResolver;
import com.comedown.calendar.infrastructure.persistence.CalendarEventStore;
import com.comedown.calendar.infrastructure.persistence.CategoryOverrideStore;
import com.comedown.calendar.infrastructure.persistence.StoredEvent;
import com.comedown.shared.time.InstantRange;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구글에서 읽은 일정을 저장된 일정과 맞춘다. 구글 호출은 이 트랜잭션 밖에서 끝난 뒤에 들어온다.
 * AI·규칙 분류기는 아직 없으므로 새 일정은 직접 수정 규칙만 적용받고 나머지는 미분류(가중치 1.0)로 남는다.
 */
@Service
public class CalendarSyncService implements CalendarEventSync {

    private final CalendarEventStore store;
    private final CategoryOverrideStore overrides;
    private final TitleHasher titleHasher;
    private final ServiceTime time;

    public CalendarSyncService(CalendarEventStore store, CategoryOverrideStore overrides, TitleHasher titleHasher,
            ServiceTime time) {
        this.store = store;
        this.overrides = overrides;
        this.titleHasher = titleHasher;
        this.time = time;
    }

    /** 저장 형태로 옮긴 일정(시각은 UTC 순간). */
    private record Incoming(SyncedEvent source, String titleHash, Instant start, Instant end) {
    }

    @Override
    @Transactional
    public SyncResult apply(long userId, InstantRange range, List<SyncedEvent> events,
            Set<Long> referencedEventIds) {
        Instant now = time.now();
        List<CategoryOverride> rules = overrides.findAll(userId);
        Map<String, Incoming> incoming = normalize(userId, events);
        Map<String, StoredEvent> existing = new LinkedHashMap<>();
        store.findByGoogleIds(userId, incoming.keySet()).forEach(e -> existing.put(e.googleEventId(), e));

        int created = 0;
        int updated = 0;
        int classified = 0;
        List<Long> unchanged = new ArrayList<>();
        for (Incoming in : incoming.values()) {
            SyncedEvent s = in.source();
            StoredEvent row = existing.get(s.googleEventId());
            Classification stored = row == null ? null : row.classification();
            // 분류기가 없으므로 기존 AI·규칙 분류는 그대로 두고, 직접 수정 규칙만 새로 적용한다.
            Classification automatic = stored != null && stored.source() != CategorySource.USER ? stored : null;
            Classification resolved = ClassificationResolver.resolve(
                    new com.comedown.calendar.domain.EventKeys(s.googleEventId(), s.recurringEventId(), in.titleHash()),
                    rules, stored, automatic);
            if (resolved != null && !resolved.equals(stored)) {
                classified++;
            }
            if (row == null) {
                store.insert(userId, s.googleEventId(), s.recurringEventId(), in.titleHash(), in.start(), in.end(),
                        s.allDay(), status(s), transparency(s), s.createdByApp(), resolved, s.googleUpdatedAt(), now);
                created++;
            } else if (changed(row, in, resolved)) {
                store.update(row.id(), s.recurringEventId(), in.titleHash(), in.start(), in.end(), s.allDay(),
                        status(s), transparency(s), s.createdByApp(), resolved, s.googleUpdatedAt(), now);
                updated++;
            } else {
                unchanged.add(row.id());
            }
        }
        store.touch(unchanged, now);

        // 범위 안에 저장돼 있는데 구글 응답에 없는 일정은 사라진 것이다.
        List<Long> gone = store.findOverlapping(userId, range.start(), range.endExclusive()).stream()
                .filter(e -> !"cancelled".equalsIgnoreCase(e.eventStatus()))
                .filter(e -> !incoming.containsKey(e.googleEventId()))
                .map(StoredEvent::id).toList();
        if (!gone.isEmpty()) {
            store.removeOrCancel(userId, gone, referencedEventIds);
        }
        return new SyncResult(incoming.size(), created, updated, gone.size(), classified);
    }

    private Map<String, Incoming> normalize(long userId, List<SyncedEvent> events) {
        Map<String, Incoming> result = new LinkedHashMap<>();
        for (SyncedEvent s : events) {
            if ("cancelled".equalsIgnoreCase(s.status())) {
                continue;
            }
            Instant start = s.allDay() ? time.startOfDay(s.startDate()) : s.start();
            Instant end = s.allDay() ? time.startOfDay(s.endDateExclusive()) : s.end();
            if (start == null || end == null || !start.isBefore(end)) {
                continue;
            }
            result.put(s.googleEventId(), new Incoming(s, titleHasher.hash(userId, s.title()), start, end));
        }
        return result;
    }

    private static boolean changed(StoredEvent row, Incoming in, Classification resolved) {
        SyncedEvent s = in.source();
        return !Objects.equals(row.recurringEventId(), s.recurringEventId())
                || !Objects.equals(row.titleHash(), in.titleHash())
                || !row.startAt().equals(in.start()) || !row.endAt().equals(in.end())
                || row.allDay() != s.allDay()
                || !Objects.equals(row.eventStatus(), status(s))
                || !Objects.equals(row.transparency(), transparency(s))
                || row.createdByApp() != s.createdByApp()
                || !Objects.equals(row.classification(), resolved);
    }

    private static String status(SyncedEvent s) {
        return s.status() == null ? "confirmed" : s.status().toLowerCase();
    }

    private static String transparency(SyncedEvent s) {
        return s.transparency() == null ? "opaque" : s.transparency().toLowerCase();
    }
}
