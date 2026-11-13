package com.comedown.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.comedown.support.MutableClock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** local 전용 날짜 이동 시계. 날짜만 옮기고 시각은 실제 시계를 따른다. */
class AdjustableClockTest {

    private final MutableClock real = MutableClock.at("2026-11-12T07:30:00+09:00");
    private final AdjustableClock clock = new AdjustableClock(real);
    private final ServiceTime serviceTime = new ServiceTime(clock);

    @Test
    void 이동하기_전에는_실제_시계와_같다() {
        assertThat(clock.instant()).isEqualTo(real.instant());
        assertThat(clock.offsetDays()).isZero();
    }

    @Test
    void 서울_기준_오늘을_요청_날짜로_옮기고_시각은_유지한다() {
        long offset = clock.moveTo(LocalDate.of(2026, 12, 1));

        assertThat(offset).isEqualTo(19);
        assertThat(serviceTime.today()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(serviceTime.toResponse(serviceTime.now()))
                .isEqualTo(OffsetDateTime.parse("2026-12-01T07:30:00+09:00"));
    }

    @Test
    void 이동한_뒤에도_실제_시계를_따라_서울_자정에_날짜가_넘어간다() {
        real.set("2026-11-12T23:59:00+09:00");
        clock.moveTo(LocalDate.of(2026, 12, 1));

        real.advance(Duration.ofMinutes(2));

        assertThat(serviceTime.today()).isEqualTo(LocalDate.of(2026, 12, 2));
    }

    @Test
    void UTC로는_전날인_서울_새벽에도_서울_날짜_기준으로_옮긴다() {
        real.set("2026-11-12T00:30:00+09:00"); // UTC 2026-11-11 15:30

        clock.moveTo(LocalDate.of(2026, 11, 13));

        assertThat(clock.offsetDays()).isEqualTo(1);
        assertThat(serviceTime.today()).isEqualTo(LocalDate.of(2026, 11, 13));
    }

    @Test
    void 과거로도_옮기고_실제_오늘을_주면_원래대로_돌아간다() {
        assertThat(clock.moveTo(LocalDate.of(2026, 11, 1))).isEqualTo(-11);
        assertThat(serviceTime.today()).isEqualTo(LocalDate.of(2026, 11, 1));

        assertThat(clock.moveTo(LocalDate.of(2026, 11, 12))).isZero();
        assertThat(clock.instant()).isEqualTo(real.instant());
    }
}
