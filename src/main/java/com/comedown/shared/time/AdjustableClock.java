package com.comedown.shared.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * local 프로필 전용 시계. 실제 시계에 하루 단위 차이(offsetDays)를 더해 서버 기준 날짜를 옮긴다(28일차 시연).
 * 시각(시·분·초)은 실제 시계를 그대로 따라가고 날짜만 바뀐다. 서버 전체에 한 개라 모든 사용자에게 같이 적용된다.
 * 운영 프로필에서는 빈이 만들어지지 않는다({@link TimeConfig}).
 */
public final class AdjustableClock extends Clock {

    private final Clock base;
    private volatile long offsetDays;

    public AdjustableClock(Clock base) {
        this.base = Objects.requireNonNull(base, "base");
    }

    /**
     * 서울 기준 오늘이 {@code date}가 되도록 날짜 차이를 다시 정한다. 실제 오늘을 주면 차이가 0이 된다.
     *
     * @return 실제 날짜와의 차이(일). 과거로 옮기면 음수
     */
    public long moveTo(LocalDate date) {
        Objects.requireNonNull(date, "date");
        LocalDate realToday = LocalDate.ofInstant(base.instant(), ServiceTime.ZONE);
        offsetDays = ChronoUnit.DAYS.between(realToday, date);
        return offsetDays;
    }

    public long offsetDays() {
        return offsetDays;
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.offset(base.withZone(zone), Duration.ofDays(offsetDays));
    }

    @Override
    public Instant instant() {
        // 서울은 일광 절약 시간이 없어 하루 = 24시간으로 더해도 날짜만 바뀐다.
        return base.instant().plus(Duration.ofDays(offsetDays));
    }
}
