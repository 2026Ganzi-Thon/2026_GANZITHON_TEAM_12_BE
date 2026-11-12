package com.comedown.recovery.domain.policy;

import com.comedown.recovery.domain.RecoveryStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 계산 정책 4절 하루 대표값·기준선·회복 상태({@value #CALCULATION_VERSION}).
 *
 * 처리 방식
 *  - 첫 입력 날짜부터 {@code through}(보통 오늘)까지 달력 날짜를 하루씩 진행하며 다시 계산한다.
 *    기준선이 지수 이동 평균이라 과거 값이 바뀌면 이후가 모두 바뀌므로, 재측정·컨디션 변경·삭제 때도 처음부터 재생한다.
 *  - 측정 없는 날도 내부적으로 상태를 판정하지만(갱신 정지·추세 창 이동), 결과 행은 입력이 있는 날만 만든다.
 *
 * 하루(D)의 순서
 *  1. 기준선 갱신: 적격일 D−7의 값을 하루 1번 반영(최근 7일 D−6~D 제외). 초기 기준선에 쓴 날은 다시 넣지 않는다.
 *     전날 상태가 LOW면 갱신을 건너뛰고 그 값은 버린다(정지). 기준선에서 2.5SD 이상 벗어난 값도 쓰지 않는다.
 *  2. D가 적격일이면 적격일 수를 올리고, 기준선 확정 전이면 초기 7일에 모은다. 7번째 적격일에 초기 기준선을 확정한다.
 *  3. 7일 추세 = D−6~D 적격일 대표값 평균(3개 이상일 때).
 *  4. 상태: 기준선 전 BUILDING → 추세 없음 INSUFFICIENT → LOW / NORMAL.
 *
 * 모든 수치는 계산 정책 v1의 초기값이다. 바꾸면 버전을 올리고 파생 데이터를 다시 계산한다.
 */
public final class RecoveryCalculator {

    /** hrv_daily_states.calculation_version. */
    public static final String CALCULATION_VERSION = "recovery-v1";

    static final int INITIAL_BASELINE_DAYS = 7;
    static final double HALF_LIFE_DAYS = 14;
    /** α = 1 − 0.5^(1/14) ≈ 0.0483 */
    static final double ALPHA = 1 - Math.pow(0.5, 1 / HALF_LIFE_DAYS);
    /** D의 갱신에는 D−7의 값을 쓴다(최근 7일 제외). */
    static final int UPDATE_LAG_DAYS = 7;
    static final double OUTLIER_SD = 2.5;
    static final double MIN_SD = 0.05;
    static final int TREND_WINDOW_DAYS = 7;
    static final int MIN_TREND_VALUES = 3;
    static final int CONSECUTIVE_LOW_VALUES = 3;

    private RecoveryCalculator() {
    }

    /**
     * @param days    측정 또는 컨디션을 저장한 날의 입력(날짜 중복 불가, 순서 무관)
     * @param through 계산 마지막 날짜(포함). 입력 날짜보다 앞설 수 없다
     * @return 입력이 있는 날짜의 결과(날짜 오름차순)
     */
    public static List<DailyRecoveryState> calculate(List<RecoveryDayInput> days, LocalDate through) {
        Objects.requireNonNull(days, "days");
        Objects.requireNonNull(through, "through");
        TreeMap<LocalDate, RecoveryDayInput> byDate = new TreeMap<>();
        for (RecoveryDayInput day : days) {
            if (byDate.put(day.date(), day) != null) {
                throw new IllegalArgumentException("같은 날짜의 입력이 두 번 있습니다: " + day.date());
            }
        }
        if (byDate.isEmpty()) {
            return List.of();
        }
        if (byDate.lastKey().isAfter(through)) {
            throw new IllegalArgumentException("계산 마지막 날짜 이후의 입력이 있습니다: " + byDate.lastKey());
        }

        Baseline baseline = new Baseline();
        TreeMap<LocalDate, Double> eligibleValues = new TreeMap<>();
        int validDays = 0;
        RecoveryStatus previousStatus = null;
        List<DailyRecoveryState> results = new ArrayList<>();

        for (LocalDate date = byDate.firstKey(); !date.isAfter(through); date = date.plusDays(1)) {
            // 1. 기준선 갱신 (D−7 값, 하루 1번)
            boolean frozen = baseline.established() && previousStatus == RecoveryStatus.LOW;
            if (baseline.established() && !frozen) {
                LocalDate source = date.minusDays(UPDATE_LAG_DAYS);
                Double value = eligibleValues.get(source);
                if (value != null && source.isAfter(baseline.initialEndDate)) {
                    baseline.update(value);
                }
            }

            // 2. 오늘 적격일 반영
            RecoveryDayInput input = byDate.get(date);
            if (input != null && input.eligible()) {
                eligibleValues.put(date, input.dailyLn());
                validDays++;
                if (!baseline.established()) {
                    baseline.collectInitial(date, input.dailyLn());
                }
            }

            // 3. 7일 추세 (D−6 ~ D, 교란일·컨디션 미입력일 제외)
            List<Double> recent = new ArrayList<>(eligibleValues
                    .subMap(date.minusDays(TREND_WINDOW_DAYS - 1), true, date, true)
                    .values());
            Double trend = recent.size() >= MIN_TREND_VALUES ? mean(recent) : null;

            // 4. 상태
            RecoveryStatus status = status(baseline, trend, recent);
            if (input != null) {
                results.add(toState(input, baseline, validDays, frozen, trend, status));
            }
            previousStatus = status;
        }
        return List.copyOf(results);
    }

    static RecoveryStatus status(Baseline baseline, Double trend, List<Double> recent) {
        if (!baseline.established()) {
            return RecoveryStatus.BUILDING;
        }
        if (trend == null) {
            return RecoveryStatus.INSUFFICIENT;
        }
        double lower = baseline.mean - baseline.sd();
        if (trend < lower) {
            return RecoveryStatus.LOW;
        }
        List<Double> lastValues = recent.subList(recent.size() - CONSECUTIVE_LOW_VALUES, recent.size());
        if (lastValues.stream().allMatch(v -> v < lower)) {
            return RecoveryStatus.LOW;
        }
        return RecoveryStatus.NORMAL;
    }

    private static DailyRecoveryState toState(
            RecoveryDayInput input, Baseline baseline, int validDays, boolean frozen, Double trend,
            RecoveryStatus status) {
        boolean established = baseline.established();
        Double dailyMs = input.dailyLn() == null ? null : Math.exp(input.dailyLn());
        BigDecimal changePct = (established && trend != null)
                ? BigDecimal.valueOf((Math.exp(trend - baseline.mean) - 1) * 100).setScale(1, RoundingMode.HALF_UP)
                : null;
        return new DailyRecoveryState(
                input.date(),
                input.dailyLn(),
                dailyMs,
                input.measurementCount(),
                input.qualityLevel(),
                input.confounded(),
                established,
                validDays,
                established ? baseline.mean : null,
                established ? baseline.sd() : null,
                baseline.sampleCount,
                frozen,
                trend,
                changePct,
                status,
                CALCULATION_VERSION);
    }

    static double mean(List<Double> values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /** 기준선 평균·분산(ln 단위). */
    static final class Baseline {
        private final List<Double> initialValues = new ArrayList<>();
        private LocalDate initialEndDate;
        private double mean;
        private double variance;
        private int sampleCount;

        boolean established() {
            return initialEndDate != null;
        }

        /** 초기 기준선: 첫 적격 7일의 평균·표본 표준편차(n − 1, 검토결과 Q4 임시 해석). */
        void collectInitial(LocalDate date, double value) {
            initialValues.add(value);
            sampleCount = initialValues.size();
            if (initialValues.size() == INITIAL_BASELINE_DAYS) {
                mean = RecoveryCalculator.mean(initialValues);
                double sumSq = 0;
                for (double v : initialValues) {
                    sumSq += (v - mean) * (v - mean);
                }
                variance = sumSq / (initialValues.size() - 1);
                initialEndDate = date;
            }
        }

        /** 표준편차 최소값 0.05는 쓰는 시점에 적용하고, 내부 분산은 산식대로 갱신한다. */
        double sd() {
            return Math.max(Math.sqrt(variance), MIN_SD);
        }

        /** 지수 이동 평균 갱신. 분산은 갱신 전 평균으로 계산한다. 2.5SD 이상 벗어난 값은 쓰지 않는다. */
        void update(double value) {
            double diff = value - mean;
            if (Math.abs(diff) >= OUTLIER_SD * sd()) {
                return;
            }
            mean = mean + ALPHA * diff;
            variance = (1 - ALPHA) * (variance + ALPHA * diff * diff);
            sampleCount++;
        }
    }
}
