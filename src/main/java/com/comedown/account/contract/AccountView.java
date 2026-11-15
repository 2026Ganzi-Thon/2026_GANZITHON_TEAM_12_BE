package com.comedown.account.contract;

import java.time.Instant;
import java.util.Map;

/**
 * 내 정보 읽기 모델(GET /api/me 응답의 계정 부분).
 *
 * @param calendarConnected oauth_tokens 행이 있는지
 * @param calendarScopeGranted 캘린더 범위가 허용됐는지
 * @param consents ConsentType 전체 → 현재 유효한 동의인지
 */
public record AccountView(
        long id,
        String email,
        String nickname,
        String onboardingStep,
        Instant onboardingCompletedAt,
        boolean calendarConnected,
        boolean calendarScopeGranted,
        Map<String, Boolean> consents,
        boolean hrvEnabled) {
}
