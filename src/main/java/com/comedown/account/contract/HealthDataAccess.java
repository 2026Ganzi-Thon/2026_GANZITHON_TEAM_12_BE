package com.comedown.account.contract;

import java.time.Instant;

/**
 * 건강정보 입력(측정·컨디션·CBI) 전에 확인할 계정 상태 스냅샷.
 *
 * @param onboardingCompletedAt 온보딩 완료 시각(UTC). 미완료면 null
 * @param sensitiveHrvConsented 건강정보(SENSITIVE_HRV) 동의가 유효한지(가장 최근 동의가 철회되지 않음)
 * @param hrvEnabled            측정 켜짐(user_settings.hrv_enabled). 설정 행이 없으면 false
 * @param active                탈퇴 진행 중이 아님(deletion_status = ACTIVE)
 */
public record HealthDataAccess(
        long userId,
        Instant onboardingCompletedAt,
        boolean sensitiveHrvConsented,
        boolean hrvEnabled,
        boolean active) {

    public boolean onboardingCompleted() {
        return onboardingCompletedAt != null;
    }
}
