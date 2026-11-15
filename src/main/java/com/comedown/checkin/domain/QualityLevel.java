package com.comedown.checkin.domain;

/** 측정 품질(ERD QualityLevel). RETRY·UNVERIFIED는 기준선·자동 판정에서 제외한다. */
public enum QualityLevel {
    GOOD,
    FAIR,
    RETRY,
    /** MANUAL(워치 값 입력) 참고값. 서버가 계산하지 않는다. */
    UNVERIFIED
}
