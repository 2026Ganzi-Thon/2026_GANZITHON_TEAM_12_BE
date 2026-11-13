package com.comedown.experience.devseed.application;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 시연 시나리오. 계산 정책의 임계값이 아니라 입력 데이터(측정값·컨디션)만 정한다. 판정은 실제 계산(recovery-v1)이 한다.
 */
public enum DemoScenario {

    /**
     * 일이 몰려 회복이 떨어지는 4주. 오늘이 28일차가 되도록 1~27일차를 넣고 오늘은 비워 둔다(시연에서 직접 측정).
     * <ul>
     *   <li>1~7일차: 평소(약 43~48ms) → 기준선 확정</li>
     *   <li>8~18일차: 평소 유지. 10일차 음주(교란일), 13일차 기록 없음</li>
     *   <li>19~27일차: 과부하로 낮아짐(약 33~39ms), 컨디션 2. 24일차 카페인(교란일)</li>
     * </ul>
     * 15~18일차를 정상 범위로 두어 최근 14일이 모두 낮음(LOW_RECOVERY_2W)이 되지는 않는다.
     * 초기 CBI는 두 하위척도 50점(HIGH, 고위험 아님)이고 28일차 재응답은 넣지 않는다(시연에서 직접 응답).
     */
    DEMO_OVERLOAD(
            List.of(
                    day(1, 46, 4), day(2, 44, 4), day(3, 48, 4), day(4, 45, 3), day(5, 47, 4),
                    day(6, 43, 3), day(7, 46, 4),
                    day(8, 45, 4), day(9, 47, 4), alcohol(10, 38, 3), day(11, 46, 4), day(12, 48, 4),
                    day(14, 44, 3),
                    day(15, 45, 3), day(16, 44, 3), day(17, 45, 3), day(18, 43, 3),
                    day(19, 39, 2), day(20, 37, 2), day(21, 38, 2), day(22, 36, 2), day(23, 35, 2),
                    caffeine(24, 36, 2), day(25, 34, 2), day(26, 35, 2), day(27, 33, 2)),
            28,
            uniformCbi(2));

    private final List<DemoDay> days;
    private final int todayDayIndex;
    private final Map<Integer, Integer> initialCbiOptions;

    DemoScenario(List<DemoDay> days, int todayDayIndex, Map<Integer, Integer> initialCbiOptions) {
        this.days = days;
        this.todayDayIndex = todayDayIndex;
        this.initialCbiOptions = initialCbiOptions;
    }

    /** 기록을 넣을 날(일차 오름차순). 빠진 일차는 기록 없는 날. */
    public List<DemoDay> days() {
        return days;
    }

    /** 시드 직후 오늘의 일차. 온보딩 날짜 = 오늘 − (일차 − 1). */
    public int todayDayIndex() {
        return todayDayIndex;
    }

    /** 문항 번호(1~13) → 보기(0~4). */
    public Map<Integer, Integer> initialCbiOptions() {
        return initialCbiOptions;
    }

    /**
     * 하루 기록.
     *
     * @param rmssdMs 합성 신호의 기대 RMSSD(ms). 실제 저장값은 측정 계산 결과
     * @param score   컨디션 1~5
     */
    public record DemoDay(int dayIndex, double rmssdMs, int score, boolean alcohol, boolean sick, boolean caffeine) {
    }

    private static DemoDay day(int dayIndex, double rmssdMs, int score) {
        return new DemoDay(dayIndex, rmssdMs, score, false, false, false);
    }

    private static DemoDay alcohol(int dayIndex, double rmssdMs, int score) {
        return new DemoDay(dayIndex, rmssdMs, score, true, false, false);
    }

    private static DemoDay caffeine(int dayIndex, double rmssdMs, int score) {
        return new DemoDay(dayIndex, rmssdMs, score, false, false, true);
    }

    private static Map<Integer, Integer> uniformCbi(int option) {
        return IntStream.rangeClosed(1, 13).boxed().collect(Collectors.toUnmodifiableMap(no -> no, no -> option));
    }
}
