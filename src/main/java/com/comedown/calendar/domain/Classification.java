package com.comedown.calendar.domain;

import java.util.Objects;

/** 일정 한 건의 분류 결과. */
public record Classification(
        EventCategory category,
        Integer burden,
        Boolean adjustable,
        Boolean deadline,
        CategorySource source) {

    public Classification {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(source, "source");
        if (burden != null && (burden < 1 || burden > 3)) {
            throw new IllegalArgumentException("burden must be 1..3: " + burden);
        }
    }
}
