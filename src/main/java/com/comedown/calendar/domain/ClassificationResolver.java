package com.comedown.calendar.domain;

import java.util.Collection;

/**
 * 일정의 최종 분류를 정한다. 우선순위는 THIS &gt; SERIES &gt; TITLE &gt; AI &gt; RULE이며,
 * 재동기화에서도 {@code category_source = USER}인 값은 덮어쓰지 않는다.
 */
public final class ClassificationResolver {

    private ClassificationResolver() {
    }

    /**
     * @param overrides 사용자 규칙(해당 사용자·캘린더 것만)
     * @param stored 이미 저장된 분류. 없으면 null
     * @param automatic 이번 동기화에서 AI/규칙이 낸 분류. 분류하지 못했으면 null
     * @return 최종 분류. 아무 분류도 없으면 null(미분류, 부하는 가중치 1.0으로 집계)
     */
    public static Classification resolve(EventKeys keys, Collection<CategoryOverride> overrides,
            Classification stored, Classification automatic) {
        for (CategoryScope scope : CategoryScope.values()) {
            String key = keys.keyFor(scope);
            if (key == null) {
                continue;
            }
            for (CategoryOverride rule : overrides) {
                if (rule.scope() == scope && rule.matchKey().equals(key)) {
                    return rule.toClassification();
                }
            }
        }
        if (stored != null && stored.source() == CategorySource.USER) {
            return stored;
        }
        return automatic;
    }
}
