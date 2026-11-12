package com.comedown.shared.time;

import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import org.springframework.stereotype.Component;

/**
 * 서비스 시간 규칙.
 * <ul>
 *   <li>DB에 저장하는 순간: UTC {@link Instant}</li>
 *   <li>서비스 날짜: Asia/Seoul 기준 {@link LocalDate}</li>
 *   <li>주 시작: 월요일</li>
 *   <li>응답 시각: 서울 오프셋(+09:00)을 붙인 ISO 8601</li>
 * </ul>
 */
@Component
public class ServiceTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final DayOfWeek WEEK_START = DayOfWeek.MONDAY;

    private final Clock clock;

    public ServiceTime(Clock clock) {
        this.clock = clock;
    }

    /** 현재 순간(UTC). */
    public Instant now() {
        return clock.instant();
    }

    /** 서울 기준 오늘 날짜. */
    public LocalDate today() {
        return LocalDate.ofInstant(now(), ZONE);
    }

    /** 순간 → 서울 기준 날짜. */
    public LocalDate toServiceDate(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    /** 순간 → 응답용 서울 오프셋 시각. null은 null로 돌려준다. */
    public OffsetDateTime toResponse(Instant instant) {
        return instant == null ? null : instant.atZone(ZONE).toOffsetDateTime();
    }

    /** 서울 기준 그 날짜 00:00의 순간. */
    public Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    /** 그 날짜가 속한 주의 월요일. */
    public LocalDate weekStartOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(WEEK_START));
    }

    /** 오늘이 속한 주의 월요일. */
    public LocalDate currentWeekStart() {
        return weekStartOf(today());
    }

    /**
     * 조회용 날짜 범위(from, to 모두 포함)를 {@code [from 00:00, to 다음 날 00:00)} 순간 범위로 바꾼다.
     */
    public InstantRange dateRange(LocalDate from, LocalDate toInclusive) {
        if (toInclusive.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "조회 시작일은 종료일보다 늦을 수 없어요.");
        }
        return new InstantRange(startOfDay(from), startOfDay(toInclusive.plusDays(1)));
    }
}
