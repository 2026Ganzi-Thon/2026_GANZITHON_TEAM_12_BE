package com.comedown.recommendation.domain.policy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 회복 추천(RECOVERY)과 2축 판단 규칙(2026-11-17 결정). 순수 계산이다.
 * <ul>
 *   <li>첫 적격 7일로 '내 평소'(기준선)를 만드는 동안에는 회복을 판단하지 않고 추천도 만들지 않는다.</li>
 *   <li>기준선이 생긴 뒤 그날 회복 상태가 평소보다 낮으면(LOW) 선호 활동 하나를 추천한다. 하루 한 번.</li>
 * </ul>
 * 2축 판단은 회복(LOW 여부) × 일정(이번 주가 평소보다 많은지)이다.
 */
public final class RecoverySuggestionPolicy {

    public static final String REASON_CODE = "LOW_RECOVERY";
    public static final String CALCULATION_VERSION = "recovery-v1";
    /** 주간 부하의 위험 사유 중 "평소보다 일정이 많음". */
    public static final String LOAD_HIGH_REASON = "LOAD_HIGH";

    public static final String BUILDING = "BUILDING";
    public static final String INSUFFICIENT = "INSUFFICIENT";
    public static final String OK = "OK";
    public static final String OVERLOAD_OK = "OVERLOAD_OK";
    public static final String NORMAL_LOW = "NORMAL_LOW";
    public static final String OVERLOAD_LOW = "OVERLOAD_LOW";

    private static final String STATUS_LOW = "LOW";
    private static final String STATUS_NORMAL = "NORMAL";

    private static final Map<String, String> ACTIVITY_NAMES = Map.of(
            "WALK", "걷기",
            "STRETCH", "스트레칭",
            "NAP", "낮잠",
            "YOGA", "요가",
            "MEDITATION", "명상",
            "LIGHT_RUN", "가벼운 러닝");

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

    /**
     * 2축 판단.
     *
     * @param baselineEstablished 기준선이 확정됐는지
     * @param recoveryStatus      BUILDING·INSUFFICIENT·NORMAL·LOW
     * @param overloaded          이번 주 일정이 평소보다 많은지
     */
    public static String judgment(boolean baselineEstablished, String recoveryStatus, boolean overloaded) {
        if (!baselineEstablished) {
            return BUILDING;
        }
        if (STATUS_LOW.equals(recoveryStatus)) {
            return overloaded ? OVERLOAD_LOW : NORMAL_LOW;
        }
        if (STATUS_NORMAL.equals(recoveryStatus)) {
            return overloaded ? OVERLOAD_OK : OK;
        }
        return INSUFFICIENT;
    }

    /** 회복 추천을 만드는 판단인지(회복이 평소보다 낮은 두 경우). */
    public static boolean recommends(String judgment) {
        return NORMAL_LOW.equals(judgment) || OVERLOAD_LOW.equals(judgment);
    }

    /**
     * 추천할 활동. 선호 활동의 저장 순서가 없으므로 코드 순으로 놓고 날짜마다 돌아가며 고른다(같은 날은 같은 활동).
     *
     * @param preferredActivities 사용자가 고른 활동 코드(1개 이상)
     */
    public static String pickActivity(List<String> preferredActivities, LocalDate date) {
        if (preferredActivities.isEmpty()) {
            throw new IllegalArgumentException("preferredActivities must not be empty");
        }
        List<String> sorted = preferredActivities.stream().sorted().toList();
        return sorted.get((int) Math.floorMod(date.toEpochDay(), (long) sorted.size()));
    }

    /** 제안 제목(구글 캘린더에 등록되는 일정 이름). 예: "20분 걷기". */
    public String title(String activityCode) {
        return blockLength.toMinutes() + "분 " + activityName(activityCode);
    }

    /** AI 도입 전 규칙 기반 카드 문구. */
    public String message(String activityCode, String judgment) {
        String lead = OVERLOAD_LOW.equals(judgment)
                ? "이번 주 일정이 평소보다 많고 회복이 평소보다 낮아요."
                : "오늘은 회복이 평소보다 낮아요.";
        return lead + " " + blockLength.toMinutes() + "분 " + activityName(activityCode) + " 어때요?";
    }

    private static String activityName(String activityCode) {
        String name = ACTIVITY_NAMES.get(activityCode);
        if (name == null) {
            throw new IllegalArgumentException("unknown activity: " + activityCode);
        }
        return name;
    }
}
