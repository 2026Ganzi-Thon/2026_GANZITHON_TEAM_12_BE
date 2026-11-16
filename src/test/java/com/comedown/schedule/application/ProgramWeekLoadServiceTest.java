package com.comedown.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.calendar.contract.CalendarEventReader;
import com.comedown.calendar.contract.CalendarEventSnapshot;
import com.comedown.schedule.contract.ProgramWeekLoad;
import com.comedown.shared.time.ServiceTime;
import com.comedown.support.MutableClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 온보딩 기준 n주차 부하. 온보딩 날짜는 2026-11-04(수)라 1주차는 11/4~11/10이다.
 * 구간 안 dayOffset: 0 수, 1 목, 2 금, 3 토, 4 일, 5 월, 6 화(토·일은 주말 가중).
 */
class ProgramWeekLoadServiceTest {

    private static final long USER = 7L;
    private static final LocalDate ONBOARDING = LocalDate.of(2026, 11, 4);

    private final List<CalendarEventSnapshot> calendar = new ArrayList<>();
    private boolean connected = true;
    private ProgramWeekLoadService service;

    @BeforeEach
    void setUp() {
        CalendarConnectionQuery connection = userId -> userId == USER && connected;
        CalendarEventReader reader = (userId, range) -> userId != USER ? List.of() : calendar.stream()
                .filter(e -> e.start().isBefore(range.endExclusive()) && e.end().isAfter(range.start()))
                .toList();
        service = new ProgramWeekLoadService(connection, reader,
                new ServiceTime(MutableClock.at("2026-11-12T07:30:00+09:00")));
    }

    private static Instant at(LocalDate date, int hour) {
        return ZonedDateTime.of(date, LocalTime.of(hour, 0), ServiceTime.ZONE).toInstant();
    }

    /** n주차(0 이하 포함)의 시작일부터 {@code dayOffset}일째 낮에 {@code hours}시간짜리 수업을 넣는다. */
    private void classIn(int week, int dayOffset, int hours) {
        LocalDate date = ONBOARDING.plusDays(7L * (week - 1) + dayOffset);
        calendar.add(new CalendarEventSnapshot(at(date, 9), at(date, 9 + hours), null, null, false,
                "CLASS", false, false, false, false));
    }

    @Test
    void 캘린더가_연결되지_않았으면_empty() {
        connected = false;
        classIn(1, 0, 4);

        assertThat(service.find(USER, ONBOARDING, 1, 2)).isEmpty();
    }

    @Test
    void 요청한_주차_수만큼_주차_순서로_시작일과_끝일을_돌려준다() {
        Optional<List<ProgramWeekLoad>> result = service.find(USER, ONBOARDING, 2, 4);

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(ProgramWeekLoad::week).containsExactly(2, 3, 4);
        assertThat(result.get().get(0).start()).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(result.get().get(0).end()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(result.get().get(2).start()).isEqualTo(LocalDate.of(2026, 11, 25));
    }

    @Test
    void 주차_부하는_그_7일의_일정으로_계산한다() {
        classIn(1, 0, 4);
        classIn(1, 5, 3); // 월요일(평일)
        classIn(2, 0, 4); // 2주차 일정은 1주차에 섞이지 않는다

        List<ProgramWeekLoad> weeks = service.find(USER, ONBOARDING, 1, 2).orElseThrow();

        assertThat(weeks.get(0).loadScore()).isEqualByComparingTo("7.0");
        assertThat(weeks.get(1).loadScore()).isEqualByComparingTo("4.0");
    }

    @Test
    void 직전_4개_주차_평균과_비교하고_온보딩_전_주차도_쓴다() {
        // 비교 대상: -2주차~1주차 4개가 각 10시간, 2주차는 13시간 → +30%
        for (int week = -2; week <= 1; week++) {
            classIn(week, 0, 4);
            classIn(week, 1, 4);
            classIn(week, 2, 2);
        }
        classIn(2, 0, 4);
        classIn(2, 1, 4);
        classIn(2, 2, 4);
        classIn(2, 5, 1); // 월요일(평일)

        ProgramWeekLoad week2 = service.find(USER, ONBOARDING, 2, 2).orElseThrow().get(0);

        assertThat(week2.loadScore()).isEqualByComparingTo("13.0");
        assertThat(week2.vsUsualPct()).isEqualByComparingTo("30.0");
        assertThat(week2.vsUsualUnavailableReason()).isNull();
    }

    @Test
    void 직전_주차가_거의_비어_있어도_평소_대비는_저장_한계를_넘지_않는다() {
        // 0·1주차에 6분짜리 수업 하나씩(부하 0.1) → 평균 0.05. 2주차는 매일 9시간이라 비율이 한계를 넘는다.
        for (int week = 0; week <= 1; week++) {
            LocalDate date = ONBOARDING.plusDays(7L * (week - 1));
            calendar.add(new CalendarEventSnapshot(at(date, 9), at(date, 9).plusSeconds(360), null, null, false,
                    "CLASS", false, false, false, false));
        }
        for (int day = 0; day < 7; day++) {
            classIn(2, day, 9);
        }

        ProgramWeekLoad week2 = service.find(USER, ONBOARDING, 2, 2).orElseThrow().get(0);

        assertThat(week2.loadScore()).isGreaterThan(new java.math.BigDecimal("50.1"));
        assertThat(week2.vsUsualPct()).isEqualByComparingTo("99999.9");
        assertThat(week2.vsUsualUnavailableReason()).isNull();
    }

    @Test
    void 직전_자료가_2주_미만이면_INSUFFICIENT_DATA() {
        classIn(1, 0, 4);

        ProgramWeekLoad week1 = service.find(USER, ONBOARDING, 1, 1).orElseThrow().get(0);

        assertThat(week1.vsUsualPct()).isNull();
        assertThat(week1.vsUsualUnavailableReason()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void 앞으로의_주차도_이미_잡힌_일정까지만_반영한다() {
        classIn(3, 0, 2); // 아직 오지 않은 3주차에 잡힌 일정

        List<ProgramWeekLoad> weeks = service.find(USER, ONBOARDING, 3, 3).orElseThrow();

        assertThat(weeks.get(0).loadScore()).isEqualByComparingTo("2.0");
    }

    @Test
    void 잘못된_주차_범위는_거부한다() {
        assertThatThrownBy(() -> service.find(USER, ONBOARDING, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.find(USER, ONBOARDING, 3, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
