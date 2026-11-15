package com.comedown.recommendation.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.comedown.recommendation.domain.policy.FreeSlotFinder.Interval;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class FreeSlotFinderTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 17);
    private static final Instant EARLY = DAY.atStartOfDay(SEOUL).toInstant();
    private static final Duration HALF = Duration.ofMinutes(30);
    private static final LocalTime SLEEP_START = LocalTime.of(23, 0);
    private static final LocalTime SLEEP_END = LocalTime.of(7, 0);

    private static Instant at(int hour, int minute) {
        return ZonedDateTime.of(DAY, LocalTime.of(hour, minute), SEOUL).toInstant();
    }

    private static Interval busy(int fromH, int fromM, int toH, int toM) {
        return new Interval(at(fromH, fromM), at(toH, toM));
    }

    private static List<Interval> find(List<Interval> busy, Instant notBefore, int limit) {
        return FreeSlotFinder.find(DAY, SEOUL, HALF, busy, SLEEP_START, SLEEP_END, PrepareAheadPolicy.MIN_GAP,
                notBefore, limit);
    }

    @Test
    void 일정이_없으면_수면이_끝나는_7시부터_30분_단위로_찾는다() {
        List<Interval> slots = find(List.of(), EARLY, 3);

        assertThat(slots).containsExactly(new Interval(at(7, 0), at(7, 30)), new Interval(at(7, 30), at(8, 0)),
                new Interval(at(8, 0), at(8, 30)));
    }

    @Test
    void 앞뒤_일정과는_10분_이상_띄운다() {
        // 9:00~10:00 일정: 8:30~9:00은 간격 10분 미달, 10:00~10:30도 미달 → 8:00~8:30, 10:30~
        List<Interval> slots = find(List.of(busy(9, 0, 10, 0)), at(8, 0), 3);

        assertThat(slots).containsExactly(new Interval(at(8, 0), at(8, 30)), new Interval(at(10, 30), at(11, 0)),
                new Interval(at(11, 0), at(11, 30)));
    }

    @Test
    void 수면_시간대는_자정을_넘어도_제외한다() {
        List<Interval> slots = find(List.of(busy(7, 0, 22, 40)), EARLY, 5);

        // 22:40 일정 뒤 10분 + 정렬 → 23:00부터는 수면이라 하루 안에 후보가 없다.
        assertThat(slots).isEmpty();
    }

    @Test
    void 오늘을_찾을_때는_현재_시각_이후만() {
        List<Interval> slots = find(List.of(), at(13, 10), 2);

        assertThat(slots).containsExactly(new Interval(at(13, 30), at(14, 0)), new Interval(at(14, 0), at(14, 30)));
    }

    @Test
    void 개수_제한을_지킨다() {
        assertThat(find(List.of(), EARLY, 5)).hasSize(5);
    }

    @Test
    void fits는_겹침_간격_수면_날짜를_검사한다() {
        List<Interval> busy = List.of(busy(9, 0, 10, 0));
        java.util.function.Predicate<Interval> ok = w -> FreeSlotFinder.fits(DAY, SEOUL, w, busy, SLEEP_START,
                SLEEP_END, PrepareAheadPolicy.MIN_GAP);

        assertThat(ok.test(new Interval(at(11, 0), at(11, 30)))).isTrue();
        assertThat(ok.test(new Interval(at(8, 30), at(9, 0)))).isFalse();   // 간격 부족
        assertThat(ok.test(new Interval(at(9, 30), at(10, 0)))).isFalse();  // 겹침
        assertThat(ok.test(new Interval(at(6, 30), at(7, 0)))).isFalse();   // 수면
        assertThat(ok.test(new Interval(at(22, 45), at(23, 15)))).isFalse(); // 수면 걸침
        assertThat(ok.test(new Interval(at(23, 40).plus(Duration.ofHours(1)), at(23, 50).plus(Duration.ofHours(2)))))
                .isFalse(); // 다른 날
    }
}
