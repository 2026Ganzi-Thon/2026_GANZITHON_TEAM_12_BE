package com.comedown.recovery.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.comedown.recovery.domain.RecoveryStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 계산 정책 4절(recovery-v1) 경계·결측·교란 검증.
 * 기본 시나리오: 2026-11-02(월)~11-08(일) 적격 7일 → 11-08에 초기 기준선 확정(평균 3.75, 표본 SD ≈ 0.108).
 */
class RecoveryCalculatorTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 2);
    private static final double[] BASE = {3.60, 3.90, 3.70, 3.80, 3.65, 3.85, 3.75};
    private static final double BASE_MEAN = 3.75;
    private static final double BASE_SD = Math.sqrt(0.07 / 6);
    private static final double LOWER = BASE_MEAN - BASE_SD;
    private static final double EPS = 1e-9;

    // ---------- 입력 도우미 ----------

    private static LocalDate day(int offset) {
        return START.plusDays(offset);
    }

    private static RecoveryDayInput eligible(LocalDate date, double ln) {
        return new RecoveryDayInput(date, ln, "GOOD", 1, true, false);
    }

    private static RecoveryDayInput confounded(LocalDate date, double ln) {
        return new RecoveryDayInput(date, ln, "GOOD", 1, true, true);
    }

    private static RecoveryDayInput conditionPending(LocalDate date, double ln) {
        return new RecoveryDayInput(date, ln, "GOOD", 1, false, false);
    }

    private static RecoveryDayInput conditionOnly(LocalDate date) {
        return new RecoveryDayInput(date, null, null, 0, true, false);
    }

    /** 11-02 ~ 11-08 기준선 7일 + 추가 입력. */
    private static List<RecoveryDayInput> baselineWeekPlus(RecoveryDayInput... extra) {
        List<RecoveryDayInput> days = new ArrayList<>();
        for (int i = 0; i < BASE.length; i++) {
            days.add(eligible(day(i), BASE[i]));
        }
        days.addAll(List.of(extra));
        return days;
    }

    private static Map<LocalDate, DailyRecoveryState> run(List<RecoveryDayInput> days, LocalDate through) {
        return RecoveryCalculator.calculate(days, through).stream()
                .collect(Collectors.toMap(DailyRecoveryState::date, Function.identity()));
    }

    // ---------- 초기 기준선 ----------

    @Test
    void 적격_6일까지는_BUILDING이고_7번째_적격일에_기준선_확정() {
        Map<LocalDate, DailyRecoveryState> states = run(baselineWeekPlus(), day(6));

        for (int i = 0; i < 6; i++) {
            DailyRecoveryState s = states.get(day(i));
            assertThat(s.recoveryStatus()).isEqualTo(RecoveryStatus.BUILDING);
            assertThat(s.baselineEstablished()).isFalse();
            assertThat(s.baselineValidDays()).isEqualTo(i + 1);
            assertThat(s.baselineMeanLn()).isNull();
            assertThat(s.baselineSdLn()).isNull();
            assertThat(s.changePct()).isNull();
        }

        DailyRecoveryState day7 = states.get(day(6));
        assertThat(day7.baselineEstablished()).isTrue();
        assertThat(day7.baselineValidDays()).isEqualTo(7);
        assertThat(day7.baselineSampleCount()).isEqualTo(7);
        assertThat(day7.baselineMeanLn()).isCloseTo(BASE_MEAN, within(EPS));
        // 표본 표준편차(n − 1)
        assertThat(day7.baselineSdLn()).isCloseTo(BASE_SD, within(EPS));
        // 기준선과 7일 추세가 같은 데이터 → change_pct 0, NORMAL
        assertThat(day7.trendLn()).isCloseTo(BASE_MEAN, within(EPS));
        assertThat(day7.changePct()).isEqualByComparingTo("0.0");
        assertThat(day7.recoveryStatus()).isEqualTo(RecoveryStatus.NORMAL);
        assertThat(day7.baselineFrozen()).isFalse();
        assertThat(day7.calculationVersion()).isEqualTo("recovery-v1");
        assertThat(day7.normalLowerLn()).isCloseTo(LOWER, within(EPS));
        assertThat(day7.normalUpperLn()).isCloseTo(BASE_MEAN + BASE_SD, within(EPS));
    }

    @Test
    void 초기_7일_값이_비슷하면_표준편차는_최소값_0_05() {
        List<RecoveryDayInput> days = new ArrayList<>();
        double[] values = {3.70, 3.80, 3.75, 3.72, 3.78, 3.74, 3.76}; // 표본 SD ≈ 0.034
        for (int i = 0; i < values.length; i++) {
            days.add(eligible(day(i), values[i]));
        }

        DailyRecoveryState day7 = run(days, day(6)).get(day(6));

        assertThat(day7.baselineSdLn()).isEqualTo(0.05);
    }

    @Test
    void 교란일과_컨디션_미입력일과_측정_없는_날은_적격일에서_빠진다() {
        List<RecoveryDayInput> days = List.of(
                eligible(day(0), 3.60),
                confounded(day(1), 3.90),
                conditionPending(day(2), 3.70),
                conditionOnly(day(3)),
                // day(4) 기록 없음
                eligible(day(5), 3.80),
                eligible(day(6), 3.65),
                eligible(day(7), 3.85),
                eligible(day(8), 3.75),
                eligible(day(9), 3.90),
                eligible(day(10), 3.70));

        Map<LocalDate, DailyRecoveryState> states = run(days, day(10));

        assertThat(states).doesNotContainKey(day(4));
        assertThat(states.get(day(1)).confounded()).isTrue();
        assertThat(states.get(day(1)).baselineValidDays()).isEqualTo(1);
        assertThat(states.get(day(3)).dailyLnRmssd()).isNull();
        assertThat(states.get(day(3)).measurementCount()).isZero();
        assertThat(states.get(day(9)).recoveryStatus()).isEqualTo(RecoveryStatus.BUILDING);
        assertThat(states.get(day(9)).baselineValidDays()).isEqualTo(6);
        assertThat(states.get(day(10)).baselineEstablished()).isTrue();
        assertThat(states.get(day(10)).baselineValidDays()).isEqualTo(7);
    }

    @Test
    void 기준선_전에도_7일_추세는_계산하되_change_pct는_null() {
        List<RecoveryDayInput> days = List.of(
                eligible(day(0), 3.6), eligible(day(1), 3.8), eligible(day(2), 3.7));

        DailyRecoveryState s = run(days, day(2)).get(day(2));

        assertThat(s.recoveryStatus()).isEqualTo(RecoveryStatus.BUILDING);
        assertThat(s.trendLn()).isCloseTo(3.7, within(EPS));
        assertThat(s.changePct()).isNull();
    }

    // ---------- 기준선 갱신 ----------

    @Test
    void 적격일_값은_7일_뒤에_지수_이동_평균으로_한_번_반영된다() {
        List<RecoveryDayInput> days = baselineWeekPlus(
                eligible(day(7), 3.80),   // 11-09: 11-16에 반영
                eligible(day(8), 3.75), eligible(day(9), 3.75), eligible(day(10), 3.75),
                eligible(day(11), 3.75), eligible(day(12), 3.75), eligible(day(13), 3.75),
                eligible(day(14), 3.75));

        Map<LocalDate, DailyRecoveryState> states = run(days, day(14));

        // 11-09 ~ 11-15: 초기 기준선 날짜(11-02~11-08)만 D−7이 되므로 갱신 없음
        assertThat(states.get(day(13)).baselineMeanLn()).isCloseTo(BASE_MEAN, within(EPS));
        assertThat(states.get(day(13)).baselineSampleCount()).isEqualTo(7);

        double alpha = 1 - Math.pow(0.5, 1.0 / 14);
        double expectedMean = BASE_MEAN + alpha * (3.80 - BASE_MEAN);
        double expectedVar = (1 - alpha) * (BASE_SD * BASE_SD + alpha * (3.80 - BASE_MEAN) * (3.80 - BASE_MEAN));
        DailyRecoveryState updated = states.get(day(14));
        assertThat(alpha).isCloseTo(0.0483, within(0.0001));
        assertThat(updated.baselineMeanLn()).isCloseTo(expectedMean, within(EPS));
        assertThat(updated.baselineSdLn()).isCloseTo(Math.sqrt(expectedVar), within(EPS));
        assertThat(updated.baselineSampleCount()).isEqualTo(8);
        assertThat(updated.baselineValidDays()).isEqualTo(15);
    }

    @Test
    void 측정_없는_날에도_갱신_차례는_지나가고_값은_한_번만_반영된다() {
        List<RecoveryDayInput> days = baselineWeekPlus(
                eligible(day(7), 3.80),
                eligible(day(8), 3.75),
                eligible(day(9), 3.75),
                // 11-12 ~ 11-17 기록 없음: 11-16·17·18에 11-09·10·11 값이 차례로 반영된다
                conditionOnly(day(16)));

        DailyRecoveryState s = run(days, day(16)).get(day(16));

        assertThat(s.baselineSampleCount()).isEqualTo(10);
    }

    @Test
    void 기준선에서_2_5SD_이상_벗어난_값은_갱신에_쓰지_않는다() {
        double outlier = BASE_MEAN + 2.5 * BASE_SD + 0.001;
        List<RecoveryDayInput> days = baselineWeekPlus(
                eligible(day(7), outlier),
                eligible(day(14), 3.75));

        DailyRecoveryState s = run(days, day(14)).get(day(14));

        assertThat(s.baselineMeanLn()).isCloseTo(BASE_MEAN, within(EPS));
        assertThat(s.baselineSampleCount()).isEqualTo(7);
    }

    @Test
    void LOW_다음_날은_갱신을_멈추고_그_차례의_값은_버린다() {
        List<RecoveryDayInput> days = baselineWeekPlus(
                eligible(day(7), 3.75),   // 11-09: 11-16 차례 → 정지로 버려짐
                eligible(day(8), 3.75),   // 11-10: 11-17 차례 → 반영
                eligible(day(9), 3.75),
                eligible(day(10), 3.75),
                eligible(day(11), 3.55),
                eligible(day(12), 3.55),
                eligible(day(13), 3.55),  // 11-15: 최근 3개 연속 < 평균 − 1SD → LOW
                eligible(day(14), 3.75),  // 11-16: 전날 LOW → 정지
                eligible(day(15), 3.75)); // 11-17: 전날 NORMAL → 갱신 재개

        Map<LocalDate, DailyRecoveryState> states = run(days, day(15));

        assertThat(states.get(day(13)).recoveryStatus()).isEqualTo(RecoveryStatus.LOW);
        assertThat(states.get(day(13)).baselineFrozen()).isFalse();

        DailyRecoveryState frozenDay = states.get(day(14));
        assertThat(frozenDay.baselineFrozen()).isTrue();
        assertThat(frozenDay.baselineSampleCount()).isEqualTo(7);
        assertThat(frozenDay.recoveryStatus()).isEqualTo(RecoveryStatus.NORMAL);

        DailyRecoveryState resumed = states.get(day(15));
        assertThat(resumed.baselineFrozen()).isFalse();
        // 11-09 값은 소급 반영하지 않고 11-10 값만 들어간다
        assertThat(resumed.baselineSampleCount()).isEqualTo(8);
    }

    // ---------- 추세·상태 ----------

    @Test
    void 기준선_후_최근_7일_대표값이_3개_미만이면_INSUFFICIENT() {
        List<RecoveryDayInput> days = baselineWeekPlus(conditionOnly(day(13)));

        DailyRecoveryState s = run(days, day(13)).get(day(13));

        // 창 11-09 ~ 11-15에 적격 값 없음
        assertThat(s.recoveryStatus()).isEqualTo(RecoveryStatus.INSUFFICIENT);
        assertThat(s.trendLn()).isNull();
        assertThat(s.changePct()).isNull();
        assertThat(s.baselineMeanLn()).isNotNull();
    }

    @Test
    void 교란일은_7일_추세와_3개_연속_판정에서_제외된다() {
        List<RecoveryDayInput> days = baselineWeekPlus(
                eligible(day(7), 3.60),
                eligible(day(8), 3.60),
                confounded(day(9), 3.10));

        DailyRecoveryState s = run(days, day(9)).get(day(9));

        // 창 11-05 ~ 11-11: 3.80, 3.65, 3.85, 3.75, 3.60, 3.60 (교란일 3.10 제외)
        double expectedTrend = (3.80 + 3.65 + 3.85 + 3.75 + 3.60 + 3.60) / 6;
        assertThat(s.trendLn()).isCloseTo(expectedTrend, within(EPS));
        // 최근 3개: 3.75, 3.60, 3.60 → 연속 낮음 아님
        assertThat(s.recoveryStatus()).isEqualTo(RecoveryStatus.NORMAL);
    }

    @Test
    void 추세가_평균_빼기_1SD_미만이면_LOW() {
        List<RecoveryDayInput> days = baselineWeekPlus(
                eligible(day(7), 3.30), eligible(day(8), 3.30), eligible(day(9), 3.70));

        DailyRecoveryState s = run(days, day(9)).get(day(9));

        double trend = (3.80 + 3.65 + 3.85 + 3.75 + 3.30 + 3.30 + 3.70) / 7;
        assertThat(trend).isLessThan(LOWER);
        assertThat(s.trendLn()).isCloseTo(trend, within(EPS));
        assertThat(s.recoveryStatus()).isEqualTo(RecoveryStatus.LOW);
        BigDecimal expectedPct = BigDecimal.valueOf((Math.exp(trend - BASE_MEAN) - 1) * 100)
                .setScale(1, RoundingMode.HALF_UP);
        assertThat(s.changePct()).isEqualByComparingTo(expectedPct);
        assertThat(s.changePct()).isEqualByComparingTo("-12.1");
    }

    @Test
    void 추세가_범위_안이어도_최근_대표값_3개_연속이_낮으면_LOW() {
        List<RecoveryDayInput> days = baselineWeekPlus(
                eligible(day(7), 3.60), eligible(day(8), 3.60), eligible(day(9), 3.60));

        Map<LocalDate, DailyRecoveryState> states = run(days, day(9));

        DailyRecoveryState twoLow = states.get(day(8));
        assertThat(twoLow.recoveryStatus()).isEqualTo(RecoveryStatus.NORMAL);

        DailyRecoveryState threeLow = states.get(day(9));
        assertThat(threeLow.trendLn()).isGreaterThan(LOWER);
        assertThat(threeLow.recoveryStatus()).isEqualTo(RecoveryStatus.LOW);
    }

    // ---------- 입력 범위 ----------

    @Test
    void 결과는_입력이_있는_날짜만_오름차순으로_돌려준다() {
        List<RecoveryDayInput> days = List.of(eligible(day(3), 3.7), eligible(day(0), 3.6));

        List<DailyRecoveryState> states = RecoveryCalculator.calculate(days, day(20));

        assertThat(states).extracting(DailyRecoveryState::date).containsExactly(day(0), day(3));
        assertThat(states.get(0).dailyRmssdMs()).isCloseTo(Math.exp(3.6), within(EPS));
    }

    @Test
    void 빈_입력은_빈_결과() {
        assertThat(RecoveryCalculator.calculate(List.of(), day(0))).isEmpty();
    }

    @Test
    void 같은_날짜가_두_번이거나_마지막_날짜_이후_입력이면_거부() {
        assertThatThrownBy(() -> RecoveryCalculator.calculate(
                List.of(eligible(day(0), 3.6), eligible(day(0), 3.7)), day(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecoveryCalculator.calculate(List.of(eligible(day(2), 3.6)), day(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
