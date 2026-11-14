package com.comedown.recovery.domain;

/**
 * 일별 회복 상태(ERD RecoveryStatus). 판정 순서: BUILDING → INSUFFICIENT → LOW / NORMAL.
 */
public enum RecoveryStatus {

    /** 초기 기준선(적격일 7일) 확정 전. */
    BUILDING,

    /** 기준선은 있지만 최근 7일 안의 대표값이 3개 미만. */
    INSUFFICIENT,

    /** 평소 범위. */
    NORMAL,

    /** 7일 추세 또는 최근 대표값 3개 연속이 평균 − 1SD 미만. */
    LOW
}
