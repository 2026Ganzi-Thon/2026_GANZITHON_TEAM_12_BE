package com.comedown.calendar.application;

import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.calendar.contract.CalendarEventSource;
import com.comedown.calendar.contract.SyncedEvent;
import com.comedown.calendar.infrastructure.persistence.CalendarEventStore;
import com.comedown.calendar.infrastructure.persistence.StoredEvent;
import com.comedown.schedule.contract.WeekLoadView;
import com.comedown.schedule.contract.WeekLoads;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.time.InstantRange;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 캘린더 탭 일정 조회. 저장된 일정(제목 없음)에 구글에서 실시간으로 읽은 제목을 붙인다.
 * 제목을 읽지 못해도 저장된 일정·부하는 그대로 돌려준다. 구글 호출은 DB 트랜잭션 밖에서 한다.
 */
@Service
public class CalendarEventQueryService {

    public static final int MAX_DAYS = 35;
    private static final Logger log = LoggerFactory.getLogger(CalendarEventQueryService.class);

    /** 응답용 일정. {@code title}은 읽지 못하면 null. */
    public record EventView(StoredEvent event, String title, LocalDate startDate, LocalDate endDateExclusive) {
    }

    public record Result(List<EventView> events, boolean titleUnavailable, WeekLoadView week) {
    }

    private final CalendarEventStore store;
    private final CalendarCredentialProvider credentials;
    private final CalendarEventSource source;
    private final WeekLoads weekLoads;
    private final ServiceTime time;

    public CalendarEventQueryService(CalendarEventStore store, CalendarCredentialProvider credentials,
            CalendarEventSource source, WeekLoads weekLoads, ServiceTime time) {
        this.store = store;
        this.credentials = credentials;
        this.source = source;
        this.weekLoads = weekLoads;
        this.time = time;
    }

    public Result query(long userId, LocalDate from, LocalDate toInclusive) {
        if (toInclusive.isBefore(from) || ChronoUnit.DAYS.between(from, toInclusive) + 1 > MAX_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "조회 기간은 최대 " + MAX_DAYS + "일이에요.");
        }
        InstantRange range = time.dateRange(from, toInclusive);
        List<StoredEvent> stored = store.findOverlapping(userId, range.start(), range.endExclusive()).stream()
                .filter(e -> !"cancelled".equalsIgnoreCase(e.eventStatus()))
                .toList();
        Map<String, String> titles = stored.isEmpty() ? Map.of() : readTitles(userId, range);
        boolean unavailable = titles == null;
        List<EventView> views = stored.stream()
                .map(e -> new EventView(e, unavailable ? null : titles.get(e.googleEventId()),
                        e.allDay() ? time.toServiceDate(e.startAt()) : null,
                        e.allDay() ? time.toServiceDate(e.endAt()) : null))
                .toList();
        boolean oneWeek = time.weekStartOf(from).equals(time.weekStartOf(toInclusive));
        WeekLoadView week = oneWeek ? weekLoads.find(userId, time.weekStartOf(from)).orElse(null) : null;
        return new Result(views, unavailable, week);
    }

    /** 구글에서 제목을 읽는다. 실패하면 null(제목 없이 응답). */
    private Map<String, String> readTitles(long userId, InstantRange range) {
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
