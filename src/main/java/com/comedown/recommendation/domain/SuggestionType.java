package com.comedown.recommendation.domain;

/** 제안 종류. */
public enum SuggestionType {
    /** 미리 시작하기(위험한 주의 마감 대비 작업 블록). 규칙은 일정축(PrepareAheadPolicy). */
    PRE_START,
    /** 회복 일정 추천(운동·휴식). */
    RECOVERY
}
