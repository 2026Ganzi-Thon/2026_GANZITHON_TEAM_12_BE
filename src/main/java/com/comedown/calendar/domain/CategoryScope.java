package com.comedown.calendar.domain;

/**
 * 분류 직접 수정의 적용 범위. 선언 순서가 우선순위다(THIS가 가장 높다).
 */
public enum CategoryScope {
    /** 이 일정만. match_key = google_event_id */
    THIS,
    /** 반복 일정 전체. match_key = recurring_event_id */
    SERIES,
    /** 같은 제목 일정 전체. match_key = title_hash */
    TITLE
}
