package com.comedown.calendar.domain;

import java.util.Objects;

/** 분류 수정 규칙과 대조하는 일정의 식별 키. 제목 원문은 쓰지 않는다. */
public record EventKeys(String googleEventId, String recurringEventId, String titleHash) {

    public EventKeys {
        Objects.requireNonNull(googleEventId, "googleEventId");
    }

    /** 이 범위의 규칙과 대조할 키. 해당 키가 없는 일정이면 null. */
    String keyFor(CategoryScope scope) {
        return switch (scope) {
            case THIS -> googleEventId;
            case SERIES -> recurringEventId;
            case TITLE -> titleHash;
        };
    }
}
