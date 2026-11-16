package com.comedown.recommendation.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoverySuggestionPolicyTest {

    private final RecoverySuggestionPolicy policy = new RecoverySuggestionPolicy(Duration.ofMinutes(20));

    @Test
    void 기준선_전에는_회복_상태와_일정에_상관없이_BUILDING이고_추천하지_않는다() {
        assertThat(RecoverySuggestionPolicy.judgment(false, "BUILDING", false)).isEqualTo("BUILDING");
        assertThat(RecoverySuggestionPolicy.judgment(false, "LOW", true)).isEqualTo("BUILDING");
        assertThat(RecoverySuggestionPolicy.recommends("BUILDING")).isFalse();
    }

    @Test
    void 기준선_뒤에는_회복과_일정_두_축으로_판단한다() {
        assertThat(RecoverySuggestionPolicy.judgment(true, "NORMAL", false)).isEqualTo("OK");
        assertThat(RecoverySuggestionPolicy.judgment(true, "NORMAL", true)).isEqualTo("OVERLOAD_OK");
        assertThat(RecoverySuggestionPolicy.judgment(true, "LOW", false)).isEqualTo("NORMAL_LOW");
        assertThat(RecoverySuggestionPolicy.judgment(true, "LOW", true)).isEqualTo("OVERLOAD_LOW");
    }

    @Test
    void 최근_자료가_부족하면_일정이_많아도_INSUFFICIENT이고_추천하지_않는다() {
        assertThat(RecoverySuggestionPolicy.judgment(true, "INSUFFICIENT", true)).isEqualTo("INSUFFICIENT");
        assertThat(RecoverySuggestionPolicy.judgment(true, null, false)).isEqualTo("INSUFFICIENT");
        assertThat(RecoverySuggestionPolicy.recommends("INSUFFICIENT")).isFalse();
    }

    @Test
    void 회복이_낮은_두_판단에서만_추천한다() {
        assertThat(RecoverySuggestionPolicy.recommends("NORMAL_LOW")).isTrue();
        assertThat(RecoverySuggestionPolicy.recommends("OVERLOAD_LOW")).isTrue();
        assertThat(RecoverySuggestionPolicy.recommends("OK")).isFalse();
        assertThat(RecoverySuggestionPolicy.recommends("OVERLOAD_OK")).isFalse();
        assertThat(RecoverySuggestionPolicy.recommends(null)).isFalse();
    }

    @Test
    void 일정이_많은_주는_위험_사유의_LOAD_HIGH로_본다() {
        assertThat(RecoverySuggestionPolicy.isOverloaded(List.of("DEADLINES", "LOAD_HIGH"))).isTrue();
        assertThat(RecoverySuggestionPolicy.isOverloaded(List.of("DEADLINES", "NO_REST_DAY"))).isFalse();
        assertThat(RecoverySuggestionPolicy.isOverloaded(List.of())).isFalse();
        assertThat(RecoverySuggestionPolicy.isOverloaded(null)).isFalse();
    }

    @Test
    void 활동은_선호_활동을_코드_순으로_놓고_날짜마다_돌아가며_고른다() {
        List<String> preferred = List.of("WALK", "MEDITATION", "STRETCH");
        LocalDate day = LocalDate.of(2026, 11, 12);

        String first = RecoverySuggestionPolicy.pickActivity(preferred, day);
        assertThat(RecoverySuggestionPolicy.pickActivity(List.of("STRETCH", "WALK", "MEDITATION"), day))
                .isEqualTo(first); // 입력 순서와 무관, 같은 날은 같은 활동
        assertThat(List.of(first, RecoverySuggestionPolicy.pickActivity(preferred, day.plusDays(1)),
                RecoverySuggestionPolicy.pickActivity(preferred, day.plusDays(2))))
                .containsExactlyInAnyOrder("MEDITATION", "STRETCH", "WALK");
        assertThat(RecoverySuggestionPolicy.pickActivity(preferred, day.plusDays(3))).isEqualTo(first);
        assertThat(RecoverySuggestionPolicy.pickActivity(List.of("NAP"), day)).isEqualTo("NAP");
        assertThatThrownBy(() -> RecoverySuggestionPolicy.pickActivity(List.of(), day))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 제목과_문구는_활동_이름과_길이로_만든다() {
        assertThat(policy.title("WALK")).isEqualTo("20분 걷기");
        assertThat(policy.title("LIGHT_RUN")).isEqualTo("20분 가벼운 러닝");
        assertThat(policy.message("WALK", "NORMAL_LOW")).isEqualTo("오늘은 회복이 평소보다 낮아요. 20분 걷기 어때요?");
        assertThat(policy.message("MEDITATION", "OVERLOAD_LOW"))
                .isEqualTo("이번 주 일정이 평소보다 많고 회복이 평소보다 낮아요. 20분 명상 어때요?");
        assertThatThrownBy(() -> policy.title("SWIM")).isInstanceOf(IllegalArgumentException.class);
    }
}
