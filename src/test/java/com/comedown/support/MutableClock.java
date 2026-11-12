package com.comedown.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트에서 시각을 고정하고 필요할 때 옮길 수 있는 Clock. 날짜 경계(서울 자정, 주 시작) 검증에 쓴다.
 */
public final class MutableClock extends Clock {

    private volatile Instant instant;

    private MutableClock(Instant instant) {
        this.instant = instant;
    }

    public static MutableClock at(Instant instant) {
        return new MutableClock(instant);
    }

    /** 예: {@code MutableClock.at("2026-11-12T07:30:00+09:00")} */
    public static MutableClock at(String isoOffsetDateTime) {
        return new MutableClock(OffsetDateTime.parse(isoOffsetDateTime).toInstant());
    }

    public void set(Instant instant) {
        this.instant = instant;
    }

    public void set(String isoOffsetDateTime) {
        this.instant = OffsetDateTime.parse(isoOffsetDateTime).toInstant();
    }

    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
