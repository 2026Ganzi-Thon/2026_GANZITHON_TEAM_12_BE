package com.comedown.schedule.domain;

/**
 * 일정 부하 계산에서 보는 일정 카테고리. calendar의 분류 값을 계약으로 옮겨 받을 때 쓰는 schedule 자체 표현이다.
 */
public enum LoadCategory {
    CLASS(true),
    ASSIGNMENT(true),
    EXAM(true),
    PRESENTATION(true),
    PART_TIME(true),
    CLUB(true),
    WORK_MEETING(true),
    STUDY(true),
    OTHER(true),
    EXERCISE(false),
    APPOINTMENT(false),
    PERSONAL(false),
    RECOVERY(false);

    private final boolean counted;

    LoadCategory(boolean counted) {
        this.counted = counted;
    }

    /** 부하 집계 대상 카테고리인지(개인 카테고리는 제외). */
    public boolean isCounted() {
        return counted;
    }

    /** EXAM·PRESENTATION은 가중치 ×1.5이고 마감으로 센다. */
    public boolean isHeavy() {
        return this == EXAM || this == PRESENTATION;
    }
}
