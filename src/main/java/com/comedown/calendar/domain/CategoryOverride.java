package com.comedown.calendar.domain;

import java.util.Objects;

/** 사용자의 분류 직접 수정 규칙(event_category_overrides 한 행). */
public record CategoryOverride(
        CategoryScope scope,
        String matchKey,
        EventCategory category,
        Integer burden,
        Boolean adjustable,
        boolean deadline,
        String customCategory) {

    public CategoryOverride {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(matchKey, "matchKey");
        Objects.requireNonNull(category, "category");
    }

    public CategoryOverride(CategoryScope scope, String matchKey, EventCategory category, Integer burden,
            Boolean adjustable, boolean deadline) {
        this(scope, matchKey, category, burden, adjustable, deadline, null);
    }

    Classification toClassification() {
        return new Classification(category, burden, adjustable, deadline, CategorySource.USER, customCategory);
    }
}
