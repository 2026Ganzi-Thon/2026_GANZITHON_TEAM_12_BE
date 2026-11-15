package com.comedown.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClassificationResolverTest {

    private static final EventKeys KEYS = new EventKeys("evt-1", "series-1", "hash-1");
    private static final Classification AI = new Classification(EventCategory.CLASS, 2, false, false, CategorySource.AI);

    private static CategoryOverride rule(CategoryScope scope, String key, EventCategory category) {
        return new CategoryOverride(scope, key, category, 3, true, true);
    }

    @Test
    void 규칙이_없으면_자동_분류를_쓴다() {
        assertThat(ClassificationResolver.resolve(KEYS, List.of(), null, AI)).isEqualTo(AI);
    }

    @Test
    void 아무_분류도_없으면_미분류_null() {
        assertThat(ClassificationResolver.resolve(KEYS, List.of(), null, null)).isNull();
    }

    @Test
    void 직접_수정_규칙은_자동_분류보다_우선하고_출처는_USER() {
        Classification result = ClassificationResolver.resolve(KEYS,
                List.of(rule(CategoryScope.TITLE, "hash-1", EventCategory.STUDY)), null, AI);

        assertThat(result.category()).isEqualTo(EventCategory.STUDY);
        assertThat(result.source()).isEqualTo(CategorySource.USER);
        assertThat(result.burden()).isEqualTo(3);
        assertThat(result.deadline()).isTrue();
    }

    @Test
    void THIS_가_SERIES_보다_SERIES_가_TITLE_보다_우선() {
        List<CategoryOverride> rules = List.of(
                rule(CategoryScope.TITLE, "hash-1", EventCategory.STUDY),
                rule(CategoryScope.SERIES, "series-1", EventCategory.CLUB),
                rule(CategoryScope.THIS, "evt-1", EventCategory.EXAM));

        assertThat(ClassificationResolver.resolve(KEYS, rules, null, AI).category()).isEqualTo(EventCategory.EXAM);
        assertThat(ClassificationResolver.resolve(KEYS, rules.subList(0, 2), null, AI).category())
                .isEqualTo(EventCategory.CLUB);
    }

    @Test
    void 키가_다른_규칙은_적용하지_않는다() {
        Classification result = ClassificationResolver.resolve(KEYS,
                List.of(rule(CategoryScope.THIS, "other", EventCategory.EXAM)), null, AI);

        assertThat(result).isEqualTo(AI);
    }

    @Test
    void 반복_일정이_아니면_SERIES_규칙은_적용하지_않는다() {
        EventKeys single = new EventKeys("evt-1", null, null);

        Classification result = ClassificationResolver.resolve(single,
                List.of(rule(CategoryScope.SERIES, "series-1", EventCategory.CLUB),
                        rule(CategoryScope.TITLE, "hash-1", EventCategory.STUDY)), null, AI);

        assertThat(result).isEqualTo(AI);
    }

    @Test
    void 재동기화에서_USER_분류는_자동_분류로_덮어쓰지_않는다() {
        Classification stored = new Classification(EventCategory.PERSONAL, 1, false, false, CategorySource.USER);

        assertThat(ClassificationResolver.resolve(KEYS, List.of(), stored, AI)).isEqualTo(stored);
    }

    @Test
    void 저장된_AI_분류는_새_자동_분류로_갱신된다() {
        Classification stored = new Classification(EventCategory.OTHER, 1, false, false, CategorySource.AI);

        assertThat(ClassificationResolver.resolve(KEYS, List.of(), stored, AI)).isEqualTo(AI);
    }

    @Test
    void 부담_정도는_1에서_3() {
        assertThatThrownBy(() -> new Classification(EventCategory.CLASS, 4, null, null, CategorySource.AI))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
