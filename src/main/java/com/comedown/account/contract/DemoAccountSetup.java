package com.comedown.account.contract;

import java.time.Instant;

/**
 * local 프로필 전용 시연 계정 준비 계약({@code POST /api/dev/seed}). 운영 프로필에서는 구현 빈이 없다.
 * 계정 상태(users·user_settings·user_consents)는 account만 쓰므로, 시드 흐름은 이 계약으로 계정을 준비한다.
 */
public interface DemoAccountSetup {

    /**
     * 이미 있는 사용자를 시연용 온보딩 완료 상태로 만든다. 사용자 행은 만들지 않는다. 호출하는 쪽의 트랜잭션에 참여한다.
     * <ul>
     *   <li>users: onboarding_step = DONE, onboarding_completed_at = 지정 시각</li>
     *   <li>user_settings: 측정 켜짐(hrv_enabled = TRUE). 행이 없으면 만든다</li>
     *   <li>user_consents: 유효한 건강정보(SENSITIVE_HRV) 동의가 없으면 새 동의 1건</li>
     * </ul>
     * 다른 동의(약관·캘린더 등)와 닉네임·생활 정보는 바꾸지 않는다.
     */
    void prepareOnboarded(long userId, Instant onboardingCompletedAt);
}
