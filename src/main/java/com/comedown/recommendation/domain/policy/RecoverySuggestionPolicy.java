package com.comedown.recommendation.domain.policy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 회복 추천(RECOVERY)과 2축 판단 규칙(2026-11-17 결정). 순수 계산이다.
 * <ul>
 *   <li>1주차(1~7일차)에는 HRV를 모으기만 하고 회복을 판단하지 않는다. 이번 주 일정이 평소보다 많을 때만
 *       "일정 많음"을 이유로 가볍게 움직이기를 추천한다(와이어프레임 B6-1).</li>
 *   <li>2주차부터는 <b>그날 측정값을 지난주(온보딩 기준 직전 주차) 평균과 비교</b>해 10% 이상 낮으면 추천한다.</li>
 *   <li>추천 활동은 HRV에 도움이 된다고 보고된 운동 목록에서 날짜마다 돌아가며 고른다.</li>
 * </ul>
 * 2축 판단은 회복(지난주 평균보다 10% 이상 낮은지) × 일정(이번 주가 평소보다 많은지)이다. 값은 모두 ln(RMSSD) 단위로 비교한다.
 */
public final class RecoverySuggestionPolicy {

    public static final String CALCULATION_VERSION = "recovery-suggest-v3";
    /**
     * "낮음"으로 보는 비율: 그날 값이 지난주 평균의 90% 이하(10% 이상 낮음)일 때. 조금만 낮아도 추천하면 이틀에 하루꼴로
     * 나와서 여유 폭을 둔다(2026-11-17 결정).
     */
    public static final double LOW_RATIO = 0.90;
    /** ln 단위 차이 기준: ln(0.9) ≈ −0.105. */
    static final double LOW_LN_DIFF = Math.log(LOW_RATIO);
    /** 그날 측정값이 지난주 평균보다 10% 이상 낮음. */
    public static final String REASON_LOW_RECOVERY = "LOW_RECOVERY";
    /** 회복을 판단하기 전(1주차)인데 이번 주 일정이 평소보다 많음. */
    public static final String REASON_OVERLOAD = "OVERLOAD";
    /** 주간 부하의 위험 사유 중 "평소보다 일정이 많음". */
    public static final String LOAD_HIGH_REASON = "LOAD_HIGH";
    /** 지난주 평균을 내는 데 필요한 최소 적격 측정일 수. */
    public static final int MIN_PRIOR_WEEK_DAYS = 3;

    public static final String BUILDING = "BUILDING";
    public static final String INSUFFICIENT = "INSUFFICIENT";
    public static final String OK = "OK";
    public static final String OVERLOAD_OK = "OVERLOAD_OK";
    public static final String NORMAL_LOW = "NORMAL_LOW";
    public static final String OVERLOAD_LOW = "OVERLOAD_LOW";

    /**
     * 추천 활동 목록(이 순서로 돌아간다). 유산소 운동·요가·명상은 HRV(RMSSD)를 높인다는 보고가 있고, HRV가 낮은 날에는
     * 걷기·가벼운 요가·스트레칭 같은 가벼운 회복 활동이 권장된다(근거는 계산_정책.md). 낮잠은 운동이 아니라 넣지 않는다.
     */
    static final List<String> HRV_ACTIVITIES = List.of("WALK", "STRETCH", "YOGA", "MEDITATION", "LIGHT_RUN");
    /** 회복이 낮은 날에는 강도가 있는 활동(가벼운 러닝)을 빼고 고른다. */
    static final List<String> GENTLE_ACTIVITIES = List.of("WALK", "STRETCH", "YOGA", "MEDITATION");

    private static final Map<String, String> ACTIVITY_NAMES = Map.of(
            "WALK", "걷기",
            "STRETCH", "스트레칭",
            "YOGA", "요가",
            "MEDITATION", "명상",
            "LIGHT_RUN", "가벼운 러닝");

    private static final Map<String, String> ACTIVITY_TIPS = Map.of(
            "WALK", "숨이 살짝 찰 정도로 편하게 걸어 보세요.",
            "STRETCH", "목·어깨·허리를 천천히 늘려 긴장을 풀어 보세요.",
            "YOGA", "호흡에 맞춰 쉬운 동작 위주로 해 보세요.",
            "MEDITATION", "편하게 앉아 천천히 숨을 고르며 쉬어 보세요.",
            "LIGHT_RUN", "옆 사람과 대화할 수 있는 속도로 가볍게 달려 보세요.");

    private final Duration blockLength;

    public RecoverySuggestionPolicy(Duration blockLength) {
        if (blockLength.isNegative() || blockLength.isZero()) {
            throw new IllegalArgumentException("blockLength must be positive");
        }
        this.blockLength = blockLength;
    }

    public Duration blockLength() {
        return blockLength;
    }

    /** 이번 주 일정이 평소보다 많은지(주간 부하의 위험 사유에 LOAD_HIGH가 있는지). */
    public static boolean isOverloaded(Collection<String> weekRiskReasons) {
        return weekRiskReasons != null && weekRiskReasons.contains(LOAD_HIGH_REASON);
    }

    /** 온보딩 기준 n주차(1주차 = 1~7일차). */
    public static int programWeek(int dayIndex) {
        return (Math.max(1, dayIndex) - 1) / 7 + 1;
    }

    /**
     * 2축 판단.
     *
     * @param programWeek  판단하는 날의 온보딩 기준 주차
     * @param dayLn        그날 대표 측정값(ln RMSSD). 측정이 없거나 교란 요인이 있는 날이면 null
     * @param priorWeekLn  직전 주차의 적격일 대표값들(ln RMSSD)
     * @param overloaded   이번 주 일정이 평소보다 많은지
     */
    public static String judgment(int programWeek, Double dayLn, List<Double> priorWeekLn, boolean overloaded) {
        if (programWeek <= 1) {
            return BUILDING;
        }
        if (dayLn == null || priorWeekLn.size() < MIN_PRIOR_WEEK_DAYS) {
            return INSUFFICIENT;
        }
        double priorAverage = priorWeekLn.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        if (dayLn - priorAverage <= LOW_LN_DIFF) {
            return overloaded ? OVERLOAD_LOW : NORMAL_LOW;
        }
        return overloaded ? OVERLOAD_OK : OK;
    }

    /** 추천을 만드는 이유. 추천하지 않으면 비어 있다. */
    public static Optional<String> reasonCode(String judgment, boolean overloaded) {
        if (NORMAL_LOW.equals(judgment) || OVERLOAD_LOW.equals(judgment)) {
            return Optional.of(REASON_LOW_RECOVERY);
        }
        if (BUILDING.equals(judgment) && overloaded) {
            return Optional.of(REASON_OVERLOAD);
        }
        return Optional.empty();
    }

    /**
     * 그 날짜에 추천할 활동. 목록에서 사용자의 선호 활동만 남기고(하나도 없으면 목록 전체) 날짜마다 돌아가며 고른다.
     * 같은 날은 같은 활동이다.
     *
     * @param preferredActivities 사용자가 고른 활동 코드(없어도 된다)
     */
    public static String pickActivity(String reasonCode, Collection<String> preferredActivities, LocalDate date) {
        List<String> pool = REASON_LOW_RECOVERY.equals(reasonCode) ? GENTLE_ACTIVITIES : HRV_ACTIVITIES;
        List<String> candidates = pool.stream().filter(preferredActivities::contains).toList();
        if (candidates.isEmpty()) {
            candidates = pool;
        }
        return candidates.get((int) Math.floorMod(date.toEpochDay(), (long) candidates.size()));
    }

    /** 제안 제목(구글 캘린더에 등록되는 일정 이름). 예: "20분 걷기". */
    public String title(String activityCode) {
        return blockLength.toMinutes() + "분 " + activityName(activityCode);
    }

    /** AI 도입 전 규칙 기반 카드 문구. */
    public String message(String activityCode, String reasonCode, String judgment) {
        String lead;
        if (REASON_OVERLOAD.equals(reasonCode)) {
            lead = "이번 주 일정이 평소보다 많아요. 가볍게 움직여 볼까요?";
        } else if (OVERLOAD_LOW.equals(judgment)) {
            lead = "이번 주 일정이 평소보다 많고, 오늘 측정값이 지난주 평균보다 많이 낮아요.";
        } else {
            lead = "오늘 측정값이 지난주 평균보다 많이 낮아요.";
        }
        return lead + " " + title(activityCode) + " 어때요? " + ACTIVITY_TIPS.get(activityCode);
    }

    private static String activityName(String activityCode) {
        String name = ACTIVITY_NAMES.get(activityCode);
        if (name == null) {
            throw new IllegalArgumentException("unknown activity: " + activityCode);
        }
        return name;
    }
}
