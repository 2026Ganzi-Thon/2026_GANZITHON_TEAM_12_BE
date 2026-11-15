package com.comedown.safety.domain;

/** 도움 안내 노출 조건(ERD SafetyTrigger). */
public enum SafetyTrigger {
    /** 회복 낮음 2주 연속(계산 정책 5절). */
    LOW_RECOVERY_2W,
    /** CBI 하위척도 중 하나라도 75 이상. */
    CBI_HIGH
}
