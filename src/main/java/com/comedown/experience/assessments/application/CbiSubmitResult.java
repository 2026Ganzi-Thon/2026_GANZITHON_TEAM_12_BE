package com.comedown.experience.assessments.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * {@code POST /api/cbi/assessments} 응답 201(API 명세 2절). 재시도 키 재생을 위해 그대로 저장된다.
 *
 * @param highRisk       어느 하위척도든 75 이상 → 안전 안내(C3)
 * @param onboardingStep 처리 후 온보딩 단계(INITIAL 제출로 CBI → LIFESTYLE)
 */
public record CbiSubmitResult(
        long id,
        String round,
        OffsetDateTime takenAt,
        Subscale personal,
        Subscale work,
        boolean highRisk,
        Safety safety,
        String onboardingStep) {

    /** @param score 소수 첫째 자리 표시값, level LOW·HIGH·VERY_HIGH */
    public record Subscale(BigDecimal score, String level) {
    }

    public record Safety(boolean show) {
    }
}
