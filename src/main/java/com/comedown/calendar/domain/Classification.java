package com.comedown.calendar.domain;

import java.util.Objects;

/**
 * 일정 한 건의 분류 결과.
 *
 * @param customCategory "기타"(OTHER)를 고르고 사용자가 직접 입력한 카테고리 이름. 그 외에는 null
 */
public record Classification(
        EventCategory category,
        Integer burden,
        Boolean adjustable,
        Boolean deadline,
        CategorySource source,
        String customCategory) {

    public Classification {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(source, "source");
        if (customCategory != null && category != EventCategory.OTHER) {
            throw new IllegalArgumentException("customCategory is only for OTHER: " + category);
        }
        if (burden != null && (burden < 1 || burden > 3)) {
            throw new IllegalArgumentException("burden must be 1..3: " + burden);
        }
    }

    public Classification(EventCategory category, Integer burden, Boolean adjustable, Boolean deadline,
            CategorySource source) {
        this(category, burden, adjustable, deadline, source, null);
    }
}
