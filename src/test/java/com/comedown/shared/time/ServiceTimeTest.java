package com.comedown.shared.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.support.MutableClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ServiceTimeTest {

    private final MutableClock clock = MutableClock.at("2026-11-12T07:30:00+09:00");
    private final ServiceTime time = new ServiceTime(clock);

    @Test
    void 서울_자정을_기준으로_서비스_날짜가_바뀐다() {
        clock.set(Instant.parse("2026-11-11T14:59:59Z")); // 서울 11-11 23:59:59
        assertThat(time.today()).isEqualTo(LocalDate.of(2026, 11, 11));

        clock.set(Instant.parse("2026-11-11T15:00:00Z")); // 서울 11-12 00:00
        assertThat(time.today()).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    @Test
    void 주는_월요일에_시작한다() {
        assertThat(time.weekStartOf(LocalDate.of(2026, 11, 12))).isEqualTo(LocalDate.of(2026, 11, 9)); // 목
        assertThat(time.weekStartOf(LocalDate.of(2026, 11, 16))).isEqualTo(LocalDate.of(2026, 11, 16)); // 월
        assertThat(time.weekStartOf(LocalDate.of(2026, 11, 15))).isEqualTo(LocalDate.of(2026, 11, 9)); // 일
        assertThat(time.currentWeekStart()).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void 조회_날짜_범위는_to를_포함하는_반열린_구간이다() {
        InstantRange range = time.dateRange(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 22));

        assertThat(range.start()).isEqualTo(Instant.parse("2026-11-15T15:00:00Z"));
        assertThat(range.endExclusive()).isEqualTo(Instant.parse("2026-11-22T15:00:00Z"));
        assertThat(range.contains(Instant.parse("2026-11-22T14:59:59Z"))).isTrue();
        assertThat(range.contains(Instant.parse("2026-11-22T15:00:00Z"))).isFalse();
    }

    @Test
    void 시작일이_종료일보다_늦으면_검증_오류() {
        assertThatThrownBy(() -> time.dateRange(LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 16)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 응답_시각은_서울_오프셋을_붙인다() {
        assertThat(time.toResponse(Instant.parse("2026-11-11T22:30:00Z")).toString())
                .isEqualTo("2026-11-12T07:30+09:00");
        assertThat(time.toResponse(null)).isNull();
    }

    @Test
    void 시각_범위는_start가_end보다_앞서야_하고_맞닿은_구간은_겹치지_않는다() {
        Instant t0 = Instant.parse("2026-11-12T00:00:00Z");
        Instant t1 = t0.atOffset(ZoneOffset.UTC).plusHours(1).toInstant();
        Instant t2 = t0.atOffset(ZoneOffset.UTC).plusHours(2).toInstant();

        assertThatThrownBy(() -> new InstantRange(t1, t1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new InstantRange(t0, t1).overlaps(new InstantRange(t1, t2))).isFalse();
        assertThat(new InstantRange(t0, t2).overlaps(new InstantRange(t1, t2))).isTrue();
    }
}
