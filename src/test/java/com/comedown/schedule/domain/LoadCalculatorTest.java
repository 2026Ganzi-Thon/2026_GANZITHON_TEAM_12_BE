package com.comedown.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.comedown.schedule.domain.WeekLoad.RiskLevel;
import com.comedown.schedule.domain.WeekLoad.RiskReason;
import com.comedown.schedule.domain.WeekLoad.UnavailableReason;
import com.comedown.shared.time.ServiceTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** load-v1 산식 경계 검증. 2026-11-09는 월요일이다. */
class LoadCalculatorTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 9);
    private static final List<PriorWeek> NO_HISTORY = priors(false, 0, 0, 0, 0);

    private static Instant at(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute), ServiceTime.ZONE).toInstant();
    }

    private static Instant at(int dayOffset, int hour, int minute) {
        return at(MON.plusDays(dayOffset), hour, minute);
    }

    private static LoadEvent event(int day, int fromHour, int toHour, LoadCategory category) {
        return LoadEvent.timed(at(day, fromHour, 0), at(day, toHour, 0), category);
    }

    private static List<PriorWeek> priors(boolean hasData, double... scores) {
        List<PriorWeek> list = new ArrayList<>();
        for (double score : scores) {
            list.add(new PriorWeek(BigDecimal.valueOf(score), hasData));
        }
        return list;
    }

    private static WeekLoad calc(List<LoadEvent> events) {
        return LoadCalculator.calculate(MON, events, NO_HISTORY);
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void 평일_낮_일정은_가중치_없이_시간만큼_센다() {
        WeekLoad load = calc(List.of(event(0, 10, 12, LoadCategory.CLASS)));

        assertThat(load.loadScore()).isEqualByComparingTo(d("2.0"));
        assertThat(load.totalHours()).isEqualByComparingTo(d("2.0"));
        assertThat(load.calculationVersion()).isEqualTo("load-v1");
    }

    @Test
    void 시험과_발표는_1점5배() {
        WeekLoad load = calc(List.of(
                event(0, 10, 12, LoadCategory.EXAM),
                event(1, 10, 11, LoadCategory.PRESENTATION)));

        assertThat(load.loadScore()).isEqualByComparingTo(d("4.5"));
        assertThat(load.totalHours()).isEqualByComparingTo(d("3.0"));
    }

    @Test
    void 분류_전_일정은_1점0으로_집계한다() {
        WeekLoad load = calc(List.of(event(0, 10, 12, null)));

        assertThat(load.loadScore()).isEqualByComparingTo(d("2.0"));
    }

    @Test
    void 밤_22시부터는_해당_구간만_1점5배() {
        WeekLoad load = calc(List.of(event(0, 21, 23, LoadCategory.CLASS)));

        // 21~22시 1.0 + 22~23시 1.5
        assertThat(load.loadScore()).isEqualByComparingTo(d("2.5"));
    }

    @Test
    void 새벽_07시_이전_구간도_1점5배() {
        WeekLoad load = calc(List.of(event(0, 6, 8, LoadCategory.CLASS)));

        assertThat(load.loadScore()).isEqualByComparingTo(d("2.5"));
    }

    @Test
    void 주말_시간은_1점5배이고_주말_시간으로_센다() {
        WeekLoad load = calc(List.of(event(5, 10, 12, LoadCategory.CLASS)));

        assertThat(load.loadScore()).isEqualByComparingTo(d("3.0"));
        assertThat(load.weekendHours()).isEqualByComparingTo(d("2.0"));
    }

    @Test
    void 주말_밤이어도_시간대_가중은_한_번만_1점5배() {
        // 토요일 05~07시: 심야이면서 주말이지만 시간대 조건은 하나다.
        WeekLoad load = calc(List.of(event(5, 5, 7, LoadCategory.CLASS)));

        assertThat(load.loadScore()).isEqualByComparingTo(d("3.0")); // 2시간 × 1.5
    }

    @Test
    void 시험이_평일_밤이면_두_조건을_곱해_2점25배() {
        WeekLoad load = calc(List.of(event(0, 5, 7, LoadCategory.EXAM)));

        assertThat(load.loadScore()).isEqualByComparingTo(d("4.5")); // 2시간 × 1.5 × 1.5
    }

    @Test
    void 시험이_주말_밤이어도_최대_2점25배를_넘지_않는다() {
        WeekLoad load = calc(List.of(event(5, 5, 7, LoadCategory.EXAM)));

        assertThat(load.loadScore()).isEqualByComparingTo(d("4.5")); // 2시간 × 2.25 (3.375배가 아니다)
    }

    @Test
    void 겹친_시간은_한_번만_세고_겹친_구간은_높은_가중치() {
        WeekLoad load = calc(List.of(
                event(0, 10, 12, LoadCategory.CLASS),
                event(0, 11, 13, LoadCategory.EXAM)));

        assertThat(load.totalHours()).isEqualByComparingTo(d("3.0"));
        // 10~11 1.0, 11~12 max(1.0, 1.5), 12~13 1.5
        assertThat(load.loadScore()).isEqualByComparingTo(d("4.0"));
    }

    @Test
    void 개인_카테고리_앱_일정_한가함_취소_일정은_제외한다() {
        Instant from = at(0, 10, 0);
        Instant to = at(0, 12, 0);
        List<LoadEvent> events = List.of(
                LoadEvent.timed(from, to, LoadCategory.EXERCISE),
                LoadEvent.timed(from, to, LoadCategory.APPOINTMENT),
                LoadEvent.timed(from, to, LoadCategory.PERSONAL),
                LoadEvent.timed(from, to, LoadCategory.RECOVERY),
                new LoadEvent(from, to, null, null, false, LoadCategory.CLASS, false, true, false, false),
                new LoadEvent(from, to, null, null, false, LoadCategory.CLASS, false, false, true, false),
                new LoadEvent(from, to, null, null, false, LoadCategory.CLASS, false, false, false, true));

        WeekLoad load = calc(events);

        assertThat(load.loadScore()).isEqualByComparingTo(d("0"));
        assertThat(load.totalHours()).isEqualByComparingTo(d("0"));
        assertThat(load.restDays()).isEqualTo(7);
    }

    @Test
    void 종일_일정은_시간에_넣지_않고_마감_수에만_더한다() {
        WeekLoad load = calc(List.of(
                LoadEvent.allDay(MON.plusDays(1), MON.plusDays(2), LoadCategory.ASSIGNMENT, true),
                LoadEvent.allDay(MON.plusDays(2), MON.plusDays(3), LoadCategory.EXAM, false),
                LoadEvent.allDay(MON.plusDays(3), MON.plusDays(4), LoadCategory.CLASS, false)));

        assertThat(load.loadScore()).isEqualByComparingTo(d("0"));
        assertThat(load.totalHours()).isEqualByComparingTo(d("0"));
        assertThat(load.deadlineCount()).isEqualTo(2);
        assertThat(load.restDays()).isEqualTo(7); // 종일 일정은 일정 있는 날로 보지 않는다(Q9 임시 해석)
    }

    @Test
    void 시험_발표_시간_일정도_마감으로_센다() {
        WeekLoad load = calc(List.of(
                event(0, 10, 12, LoadCategory.EXAM),
                event(1, 10, 12, LoadCategory.PRESENTATION),
                event(2, 10, 12, LoadCategory.CLASS)));

        assertThat(load.deadlineCount()).isEqualTo(2);
    }

    @Test
    void 주_경계를_넘는_일정은_주별로_나눠_센다() {
        // 일요일 23:00 ~ 다음 월요일 01:00
        LoadEvent crossing = LoadEvent.timed(at(6, 23, 0), at(7, 1, 0), LoadCategory.CLASS);

        WeekLoad thisWeek = LoadCalculator.calculate(MON, List.of(crossing), NO_HISTORY);
        WeekLoad nextWeek = LoadCalculator.calculate(MON.plusDays(7), List.of(crossing), NO_HISTORY);

        assertThat(thisWeek.totalHours()).isEqualByComparingTo(d("1.0"));
        assertThat(thisWeek.loadScore()).isEqualByComparingTo(d("1.5")); // 일요일 밤(시간대 가중 한 번)
        assertThat(nextWeek.totalHours()).isEqualByComparingTo(d("1.0"));
        assertThat(nextWeek.loadScore()).isEqualByComparingTo(d("1.5")); // 월요일 새벽
    }

    @Test
    void 심야_일정은_22시_이후_끝나거나_07시_이전_시작이다() {
        WeekLoad load = calc(List.of(
                LoadEvent.timed(at(0, 20, 0), at(0, 22, 30), LoadCategory.CLASS), // 22시 넘어 끝남
                LoadEvent.timed(at(1, 20, 0), at(1, 22, 0), LoadCategory.CLASS), // 정확히 22시 → 아님
                LoadEvent.timed(at(2, 6, 0), at(2, 9, 0), LoadCategory.CLASS), // 07시 전 시작
                LoadEvent.timed(at(3, 7, 0), at(3, 9, 0), LoadCategory.CLASS), // 정확히 07시 → 아님
                LoadEvent.timed(at(4, 23, 0), at(5, 1, 0), LoadCategory.CLASS))); // 자정을 넘김

        assertThat(load.lateNightCount()).isEqualTo(3);
    }

    @Test
    void 쉬는_날은_집계_대상_일정이_없는_날_수() {
        List<LoadEvent> events = new ArrayList<>();
        for (int day : new int[] {0, 1, 3, 4, 5, 6}) {
            events.add(event(day, 10, 11, LoadCategory.CLASS));
        }

        WeekLoad load = calc(events);

        assertThat(load.restDays()).isEqualTo(1);
        assertThat(load.riskReasons()).doesNotContain(RiskReason.NO_REST_DAY);
    }

    @Test
    void 쉬는_날이_없으면_NO_REST_DAY_1점() {
        List<LoadEvent> events = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            events.add(event(day, 10, 11, LoadCategory.CLASS));
        }

        WeekLoad load = calc(events);

        assertThat(load.restDays()).isZero();
        assertThat(load.riskReasons()).containsExactly(RiskReason.NO_REST_DAY);
        assertThat(load.riskLevel()).isEqualTo(RiskLevel.NONE); // 1점
    }

    @Test
    void 연속_일정일은_주_경계를_넘어_센다() {
        // 11/2(월) ~ 11/11(수) 10일 연속
        List<LoadEvent> events = new ArrayList<>();
        for (int day = -7; day <= 2; day++) {
            events.add(event(day, 10, 11, LoadCategory.CLASS));
        }

        WeekLoad load = calc(events);

        assertThat(load.riskReasons()).contains(RiskReason.LONG_STREAK);
    }

    @Test
    void 연속_9일이면_LONG_STREAK이_아니다() {
        List<LoadEvent> events = new ArrayList<>();
        for (int day = -6; day <= 2; day++) {
            events.add(event(day, 10, 11, LoadCategory.CLASS));
        }

        assertThat(calc(events).riskReasons()).doesNotContain(RiskReason.LONG_STREAK);
    }

    @Test
    void 직전_데이터가_2주_미만이면_INSUFFICIENT_DATA() {
        List<PriorWeek> prior = List.of(
                new PriorWeek(BigDecimal.TEN, true),
                new PriorWeek(BigDecimal.ZERO, false),
                new PriorWeek(BigDecimal.ZERO, false),
                new PriorWeek(BigDecimal.ZERO, false));

        WeekLoad load = LoadCalculator.calculate(MON, List.of(event(0, 10, 12, LoadCategory.CLASS)), prior);

        assertThat(load.vsUsualPct()).isNull();
        assertThat(load.vsUsualUnavailableReason()).isEqualTo(UnavailableReason.INSUFFICIENT_DATA);
    }

    @Test
    void 직전_4주_평균이_0이면_ZERO_BASELINE() {
        List<PriorWeek> prior = priors(true, 0, 0, 0, 0);

        WeekLoad load = LoadCalculator.calculate(MON, List.of(event(0, 10, 12, LoadCategory.CLASS)), prior);

        assertThat(load.vsUsualPct()).isNull();
        assertThat(load.vsUsualUnavailableReason()).isEqualTo(UnavailableReason.ZERO_BASELINE);
    }

    @Test
    void vs_usual은_직전_4주_평균_대비_증감률() {
        WeekLoad load = LoadCalculator.calculate(MON, weeklyHours(13), priors(true, 10, 10, 10, 10));

        assertThat(load.loadScore()).isEqualByComparingTo(d("13.0"));
        assertThat(load.vsUsualPct()).isEqualByComparingTo(d("30.0"));
        assertThat(load.vsUsualUnavailableReason()).isNull();
    }

    @Test
    void 부하_30퍼센트_이상은_2점_WATCH() {
        WeekLoad load = LoadCalculator.calculate(MON, weeklyHours(13), priors(true, 10, 10, 10, 10));

        assertThat(load.riskReasons()).containsExactly(RiskReason.LOAD_HIGH);
        assertThat(load.riskLevel()).isEqualTo(RiskLevel.WATCH);
    }

    @Test
    void 부하_15퍼센트_이상은_1점이라_NONE이지만_사유에_남는다() {
        WeekLoad load = LoadCalculator.calculate(MON, weeklyHours(11.5), priors(true, 10, 10, 10, 10));

        assertThat(load.vsUsualPct()).isEqualByComparingTo(d("15.0"));
        assertThat(load.riskReasons()).containsExactly(RiskReason.LOAD_HIGH);
        assertThat(load.riskLevel()).isEqualTo(RiskLevel.NONE);
    }

    @Test
    void 부하_15퍼센트_미만은_점수가_없다() {
        WeekLoad load = LoadCalculator.calculate(MON, weeklyHours(11), priors(true, 10, 10, 10, 10));

        assertThat(load.riskReasons()).isEmpty();
    }

    @Test
    void 마감_2개는_1점_3개는_2점() {
        WeekLoad two = calc(List.of(deadline(1), deadline(2)));
        WeekLoad three = calc(List.of(deadline(1), deadline(2), deadline(3)));

        assertThat(two.riskReasons()).containsExactly(RiskReason.DEADLINES);
        assertThat(two.riskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(three.riskLevel()).isEqualTo(RiskLevel.WATCH);
    }

    @Test
    void 점수_합이_4점_이상이면_RISK() {
        List<LoadEvent> events = new ArrayList<>(weeklyHours(13));
        events.add(deadline(4));
        events.add(deadline(5));
        events.add(deadline(6));

        WeekLoad load = LoadCalculator.calculate(MON, events, priors(true, 10, 10, 10, 10));

        // LOAD_HIGH 2 + DEADLINES 2
        assertThat(load.riskReasons()).containsExactly(RiskReason.LOAD_HIGH, RiskReason.DEADLINES);
        assertThat(load.riskLevel()).isEqualTo(RiskLevel.RISK);
    }

    @Test
    void 심야_3회는_1점() {
        List<LoadEvent> events = List.of(
                LoadEvent.timed(at(0, 20, 0), at(0, 23, 0), LoadCategory.CLASS),
                LoadEvent.timed(at(1, 20, 0), at(1, 23, 0), LoadCategory.CLASS),
                LoadEvent.timed(at(2, 20, 0), at(2, 23, 0), LoadCategory.CLASS));

        assertThat(calc(events).riskReasons()).containsExactly(RiskReason.LATE_NIGHT);
    }

    @Test
    void 월요일이_아닌_시작일은_거부한다() {
        assertThatThrownBy(() -> LoadCalculator.calculate(MON.plusDays(1), List.of(), NO_HISTORY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 직전_주가_4개가_아니면_거부한다() {
        assertThatThrownBy(() -> LoadCalculator.calculate(MON, List.of(), priors(true, 1, 2, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 월요일이_아닌_날부터_7일_구간도_계산한다() {
        // 목요일(11/12)부터 7일: 토요일(11/14) 낮 2시간 = 주말 가중 3.0, 수요일(11/18) 낮 1시간
        LocalDate thursday = MON.plusDays(3);
        List<LoadEvent> events = List.of(
                LoadEvent.timed(at(5, 10, 0), at(5, 12, 0), LoadCategory.CLASS),
                LoadEvent.timed(at(9, 10, 0), at(9, 11, 0), LoadCategory.CLASS),
                LoadEvent.timed(at(10, 10, 0), at(10, 12, 0), LoadCategory.CLASS)); // 구간 밖(11/19)

        WeekLoad load = LoadCalculator.calculateWindow(thursday, events, NO_HISTORY);

        assertThat(load.weekStart()).isEqualTo(thursday);
        assertThat(load.totalHours()).isEqualByComparingTo(d("3.0"));
        assertThat(load.loadScore()).isEqualByComparingTo(d("4.0")); // 3.0 + 1.0
        assertThat(load.weekendHours()).isEqualByComparingTo(d("2.0"));
        assertThat(load.restDays()).isEqualTo(5);
    }

    @Test
    void calculate는_월요일이_아니면_여전히_거부한다() {
        assertThatThrownBy(() -> LoadCalculator.calculate(MON.plusDays(3), List.of(), NO_HISTORY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LoadEvent deadline(int day) {
        return LoadEvent.allDay(MON.plusDays(day), MON.plusDays(day + 1), LoadCategory.ASSIGNMENT, true);
    }

    /** 월~목 낮 시간에 총 {@code hours}시간(평일·낮이라 가중치 없음, 마감·심야 없음)을 만든다. */
    private static List<LoadEvent> weeklyHours(double hours) {
        List<LoadEvent> events = new ArrayList<>();
        double remaining = hours;
        for (int day = 0; day < 4 && remaining > 0; day++) {
            double today = Math.min(remaining, 4);
            long minutes = Math.round(today * 60);
            events.add(LoadEvent.timed(at(day, 9, 0), at(day, 9, 0).plusSeconds(minutes * 60),
                    LoadCategory.CLASS));
            remaining -= today;
        }
        return events;
    }
}
