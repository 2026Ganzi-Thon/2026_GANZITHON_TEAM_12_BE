package com.comedown.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.comedown.calendar.contract.CalendarEventReader;
import com.comedown.calendar.contract.CalendarEventSnapshot;
import com.comedown.schedule.domain.WeekLoad;
import com.comedown.shared.time.InstantRange;
import com.comedown.shared.time.ServiceTime;
import com.comedown.support.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WeeklyLoadServiceTest {

    private static final long USER = 7L;
    private static final LocalDate MON = LocalDate.of(2026, 11, 9);

    private final List<CalendarEventSnapshot> calendar = new ArrayList<>();
    private final InMemoryStore store = new InMemoryStore();
    private final MutableClock clock = MutableClock.at("2026-11-12T07:30:00+09:00");
    private WeeklyLoadService service;

    @BeforeEach
    void setUp() {
        CalendarEventReader reader = (userId, range) -> userId != USER ? List.of() : calendar.stream()
                .filter(e -> e.allDay() ? true : e.start().isBefore(range.endExclusive()) && e.end().isAfter(range.start()))
                .toList();
        service = new WeeklyLoadService(reader, store, new ServiceTime(clock));
    }

    private static Instant at(LocalDate date, int hour) {
        return ZonedDateTime.of(date, LocalTime.of(hour, 0), ServiceTime.ZONE).toInstant();
    }

    private void classHours(LocalDate date, int fromHour, int toHour) {
        calendar.add(new CalendarEventSnapshot(at(date, fromHour), at(date, toHour), null, null, false,
                "CLASS", false, false, false, false));
    }

    @Test
    void 재계산한_주를_저장하고_계산_시각을_남긴다() {
        classHours(MON, 10, 12);

        List<WeekLoad> result = service.recalculate(USER, List.of(MON));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).loadScore()).isEqualByComparingTo("2.0");
        assertThat(store.rows).containsKey(MON);
        assertThat(store.computedAt.get(MON)).isEqualTo(Instant.parse("2026-11-11T22:30:00Z"));
    }

    @Test
    void 일정이_없는_주도_행을_만든다() {
        service.recalculate(USER, List.of(MON));

        WeekLoad load = store.rows.get(MON);
        assertThat(load.loadScore()).isEqualByComparingTo("0");
        assertThat(load.restDays()).isEqualTo(7);
    }

    @Test
    void 여러_주는_오래된_주부터_계산해_다음_주의_직전_4주에_반영한다() {
        // 직전 4주(10/12~11/2)에 매주 10시간, 이번 주(11/9)에 13시간
        for (int w = 4; w >= 1; w--) {
            LocalDate week = MON.minusWeeks(w);
            classHours(week, 9, 13);
            classHours(week.plusDays(1), 9, 13);
            classHours(week.plusDays(2), 9, 11);
        }
        classHours(MON, 9, 13);
        classHours(MON.plusDays(1), 9, 13);
        classHours(MON.plusDays(2), 9, 13);
        classHours(MON.plusDays(3), 9, 10);

        // 일부러 뒤섞인 순서와 중복으로 넘긴다.
        List<WeekLoad> result = service.recalculate(USER,
                List.of(MON, MON.minusWeeks(2), MON.minusWeeks(4), MON.minusWeeks(1), MON.minusWeeks(3), MON));

        assertThat(result).extracting(WeekLoad::weekStart).containsExactly(
                MON.minusWeeks(4), MON.minusWeeks(3), MON.minusWeeks(2), MON.minusWeeks(1), MON);
        WeekLoad thisWeek = store.rows.get(MON);
        assertThat(thisWeek.vsUsualPct()).isEqualByComparingTo("30.0");
    }

    @Test
    void 직전_데이터가_없으면_INSUFFICIENT_DATA() {
        classHours(MON, 10, 12);

        service.recalculate(USER, List.of(MON));

        assertThat(store.rows.get(MON).vsUsualUnavailableReason())
                .isEqualTo(WeekLoad.UnavailableReason.INSUFFICIENT_DATA);
    }

    @Test
    void 다시_계산하면_기존_행을_갱신한다() {
        classHours(MON, 10, 12);
        service.recalculate(USER, List.of(MON));
        classHours(MON.plusDays(1), 10, 12);

        service.recalculate(USER, List.of(MON));

        assertThat(store.rows).hasSize(1);
        assertThat(store.rows.get(MON).loadScore()).isEqualByComparingTo("4.0");
    }

    @Test
    void 조회는_from이_속한_주부터_count주를_돌려준다() {
        service.recalculate(USER, List.of(MON.minusWeeks(1), MON, MON.plusWeeks(1), MON.plusWeeks(5)));

        List<WeekLoad> weeks = service.weeks(USER, MON.plusDays(3), 3);

        assertThat(weeks).extracting(WeekLoad::weekStart).containsExactly(MON, MON.plusWeeks(1));
    }

    @Test
    void 마지막_계산_시각은_범위_안_행_중_가장_늦은_값이고_행이_없으면_비어_있다() {
        service.recalculate(USER, List.of(MON.minusWeeks(1), MON, MON.plusWeeks(1)));
        clock.set("2026-11-12T09:00:00+09:00");
        service.recalculate(USER, List.of(MON.minusWeeks(1), MON.plusWeeks(1)));

        // 수요일을 넘겨도 그 주(11/9)부터 센다. 범위 밖인 지난주(11/2)의 나중 계산은 보지 않는다.
        assertThat(service.lastComputedAt(USER, MON.plusDays(2), 1)).contains(Instant.parse("2026-11-11T22:30:00Z"));
        assertThat(service.lastComputedAt(USER, MON, 2)).contains(Instant.parse("2026-11-12T00:00:00Z"));
        assertThat(service.lastComputedAt(USER, MON.plusWeeks(2), 4)).isEmpty();
    }

    private static final class InMemoryStore implements WeeklyLoadStore {
        final Map<LocalDate, WeekLoad> rows = new TreeMap<>();
        final Map<LocalDate, Instant> computedAt = new TreeMap<>();

        @Override
        public Optional<WeekLoad> find(long userId, LocalDate weekStart) {
            return Optional.ofNullable(rows.get(weekStart));
        }

        @Override
        public List<WeekLoad> findBetween(long userId, LocalDate from, LocalDate toInclusive) {
            return rows.values().stream()
                    .filter(w -> !w.weekStart().isBefore(from) && !w.weekStart().isAfter(toInclusive))
                    .toList();
        }

        @Override
        public Optional<Instant> lastComputedAt(long userId, LocalDate from, LocalDate toInclusive) {
            return computedAt.entrySet().stream()
                    .filter(e -> !e.getKey().isBefore(from) && !e.getKey().isAfter(toInclusive))
                    .map(Map.Entry::getValue).max(Instant::compareTo);
        }

        @Override
        public void save(long userId, WeekLoad load, Instant at) {
            rows.put(load.weekStart(), load);
            computedAt.put(load.weekStart(), at);
        }

        @Override
        public int deleteAll(long userId) {
            int n = rows.size();
            rows.clear();
            computedAt.clear();
            return n;
        }
    }
}
