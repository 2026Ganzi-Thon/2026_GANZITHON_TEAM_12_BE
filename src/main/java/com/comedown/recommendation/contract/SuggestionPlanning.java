package com.comedown.recommendation.contract;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * recommendation이 공개하는 제안 시간 계획 계약(빈 시간 탐색·PRE_START 규칙). 계산은 recommendation 도메인 정책이
 * 하고, 여러 기능을 묶는 experience는 이 계약만 쓴다.
 */
public interface SuggestionPlanning {

    /** 반열린 구간 {@code [start, end)}. */
    record TimeSlot(Instant start, Instant end) {
    }

    /**
     * 서울 기준 {@code day} 안의 빈 시간을 이른 순으로 찾는다. 수면 시간대는 빼고, 막힌 시간 앞뒤로 최소 간격을 둔다.
     *
     * @param busy      막힌 시간(시간 일정·진행 중인 제안)
     * @param notBefore 이 시각 이전은 쓰지 않는다
     * @param limit     최대 개수
     */
    List<TimeSlot> freeSlots(LocalDate day, Duration length, List<TimeSlot> busy, LocalTime sleepStart,
            LocalTime sleepEnd, Instant notBefore, int limit);

    /** {@code wanted}가 그 날 빈 시간에 그대로 들어가는지(다른 시간 승인 검증). */
    boolean fits(LocalDate day, TimeSlot wanted, List<TimeSlot> busy, LocalTime sleepStart, LocalTime sleepEnd);

    // ---------- PRE_START 규칙 ----------

    /** 위험 주인지(주간 부하의 위험 수준으로 판단). */
    boolean isPreStartRiskWeek(String riskLevel);

    /** 준비 블록을 찾아볼 날들(마감 전날부터 오늘까지 거슬러 올라가는 순서). */
    List<LocalDate> preStartCandidateDays(LocalDate today, LocalDate deadlineDate);

    Duration preStartBlockLength();

    /** 하루 제안(승인 예약 포함) 최대 개수. */
    int maxSuggestionsPerDay();

    /** 그 날짜 제안의 만료 시각. */
    Instant preStartExpiresAt(LocalDate targetDate);

    String preStartReasonCode();

    String preStartCalculationVersion();

    // ---------- RECOVERY 규칙·2축 판단 ----------

    /** 이번 주 일정이 평소보다 많은지(주간 부하의 위험 사유로 판단). */
    boolean isOverloadedWeek(List<String> weekRiskReasons);

    /**
     * 2축 판단(회복 × 이번 주 일정): OK·OVERLOAD_OK·OVERLOAD_LOW·NORMAL_LOW·BUILDING·INSUFFICIENT.
     *
     * @param recoveryStatus BUILDING·INSUFFICIENT·NORMAL·LOW
     */
    String judgment(boolean baselineEstablished, String recoveryStatus, boolean overloaded);

    /** 회복 추천을 만드는 판단인지. */
    boolean recommendsRecovery(String judgment);

    /** 선호 활동 중 그 날짜에 추천할 활동 코드. */
    String pickRecoveryActivity(List<String> preferredActivities, LocalDate date);

    Duration recoveryBlockLength();

    String recoveryTitle(String activityCode);

    /** AI 도입 전 규칙 기반 카드 문구. */
    String recoveryMessage(String activityCode, String judgment);

    String recoveryReasonCode();

    String recoveryCalculationVersion();

    /** 그 날짜 제안의 만료 시각(종류와 무관하게 같다). */
    Instant suggestionExpiresAt(LocalDate targetDate);
}
