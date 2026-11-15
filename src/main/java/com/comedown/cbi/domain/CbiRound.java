package com.comedown.cbi.domain;

/** CBI 회차(ERD CbiRound). MVP는 INITIAL·DAY28만 받는다. FOLLOW_UP은 회차 번호 설계 후 활성화한다. */
public enum CbiRound {
    INITIAL,
    DAY28,
    FOLLOW_UP;

    public boolean supported() {
        return this != FOLLOW_UP;
    }
}
