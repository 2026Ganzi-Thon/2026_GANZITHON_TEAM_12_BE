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
}
