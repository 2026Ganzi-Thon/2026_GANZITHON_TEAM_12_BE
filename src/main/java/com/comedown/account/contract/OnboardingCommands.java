package com.comedown.account.contract;

/**
 * account가 공개하는 온보딩 단계 전진 계약. 단계 값은 account만 쓴다.
 * 초기 CBI 제출(experience.assessments)이 CBI 단계를 마칠 때 호출한다.
 */
public interface OnboardingCommands {

    /**
     * 현재 단계가 CBI면 LIFESTYLE로 전진한다. 다른 단계면 바꾸지 않는다(완료된 단계를 되돌리지 않음).
     * 호출하는 쪽의 트랜잭션에 참여한다.
     *
     * @return 처리 후 현재 단계
     */
    String completeCbiStep(long userId);

    /**
     * 건강정보 동의 없이 CBI 단계를 건너뛴다(현재 CBI 단계이고 SENSITIVE_HRV 동의가 없을 때만).
     * 프로필 단계 전이면 409 ONBOARDING_STEP_INVALID, 동의가 있으면 409 INVALID_STATE. 이미 지난 단계면 바꾸지 않는다.
     *
     * @return 처리 후 현재 단계
     */
    String skipCbiStep(long userId);

    /**
     * SCHEDULE_REPORT 단계를 마치고 온보딩을 끝낸다(완료 시각 기록). 이미 끝났으면 기존 완료 시각을 돌려준다.
     * 이전 단계라면 409 ONBOARDING_STEP_INVALID.
     *
     * @return 온보딩 완료 시각(UTC)
     */
    java.time.Instant completeOnboarding(long userId);
}
