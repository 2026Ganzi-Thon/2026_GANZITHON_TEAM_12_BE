package com.comedown.recommendation.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoverySuggestionPolicyTest {

    private static final List<Double> PRIOR = List.of(3.70, 3.80, 3.90); // 평균 3.80
    private final RecoverySuggestionPolicy policy = new RecoverySuggestionPolicy(Duration.ofMinutes(20));

    @Test
    void 주차는_온보딩_기준_7일_단위다() {
        assertThat(RecoverySuggestionPolicy.programWeek(1)).isEqualTo(1);
        assertThat(RecoverySuggestionPolicy.programWeek(7)).isEqualTo(1);
        assertThat(RecoverySuggestionPolicy.programWeek(8)).isEqualTo(2);
        assertThat(RecoverySuggestionPolicy.programWeek(14)).isEqualTo(2);
        assertThat(RecoverySuggestionPolicy.programWeek(15)).isEqualTo(3);
        assertThat(RecoverySuggestionPolicy.programWeek(0)).isEqualTo(1);
    }

    @Test
    void 첫_주에는_측정값과_상관없이_BUILDING이다() {
        assertThat(RecoverySuggestionPolicy.judgment(1, 3.0, PRIOR, false)).isEqualTo("BUILDING");
        assertThat(RecoverySuggestionPolicy.judgment(1, null, List.of(), true)).isEqualTo("BUILDING");
    }

    @Test
    void 둘째_주부터는_그날_값이_지난주_평균보다_10퍼센트_이상_낮으면_낮음이다() {
        // 평균 ln 3.80 → 낮음 기준은 3.80 + ln(0.9) ≈ 3.6946
        assertThat(RecoverySuggestionPolicy.judgment(2, 3.69, PRIOR, false)).isEqualTo("NORMAL_LOW");
        assertThat(RecoverySuggestionPolicy.judgment(2, 3.60, PRIOR, true)).isEqualTo("OVERLOAD_LOW");
        assertThat(RecoverySuggestionPolicy.judgment(2, 3.81, PRIOR, false)).isEqualTo("OK");
        assertThat(RecoverySuggestionPolicy.judgment(3, 3.81, PRIOR, true)).isEqualTo("OVERLOAD_OK");
    }

    @Test
    void 지난주_평균보다_낮아도_10퍼센트_미만이면_낮은_것이_아니다() {
        assertThat(RecoverySuggestionPolicy.judgment(2, 3.80, List.of(3.80, 3.80, 3.80), false)).isEqualTo("OK");
        assertThat(RecoverySuggestionPolicy.judgment(2, 3.79, PRIOR, false)).isEqualTo("OK");
        assertThat(RecoverySuggestionPolicy.judgment(2, 3.70, PRIOR, true)).isEqualTo("OVERLOAD_OK"); // 약 9.5% 낮음
        // ms로 보면: 평균 50ms일 때 45ms(정확히 10% 낮음)부터 낮음, 46ms는 아님.
        List<Double> fifty = List.of(Math.log(50), Math.log(50), Math.log(50));
        assertThat(RecoverySuggestionPolicy.judgment(2, Math.log(44.9), fifty, false)).isEqualTo("NORMAL_LOW");
        assertThat(RecoverySuggestionPolicy.judgment(2, Math.log(46), fifty, false)).isEqualTo("OK");
    }

    @Test
    void 그날_값이_없거나_지난주_측정이_3일_미만이면_INSUFFICIENT다() {
        assertThat(RecoverySuggestionPolicy.judgment(2, null, PRIOR, true)).isEqualTo("INSUFFICIENT");
        assertThat(RecoverySuggestionPolicy.judgment(2, 3.0, List.of(3.8, 3.9), false)).isEqualTo("INSUFFICIENT");
        assertThat(RecoverySuggestionPolicy.judgment(2, 3.0, List.of(), false)).isEqualTo("INSUFFICIENT");
    }

    @Test
    void 회복이_낮으면_LOW_RECOVERY로_첫_주에_일정이_많으면_OVERLOAD로_추천한다() {
        assertThat(RecoverySuggestionPolicy.reasonCode("NORMAL_LOW", false)).contains("LOW_RECOVERY");
        assertThat(RecoverySuggestionPolicy.reasonCode("OVERLOAD_LOW", true)).contains("LOW_RECOVERY");
        assertThat(RecoverySuggestionPolicy.reasonCode("BUILDING", true)).contains("OVERLOAD");
        assertThat(RecoverySuggestionPolicy.reasonCode("BUILDING", false)).isEmpty();
        assertThat(RecoverySuggestionPolicy.reasonCode("OK", false)).isEmpty();
        assertThat(RecoverySuggestionPolicy.reasonCode("OVERLOAD_OK", true)).isEmpty();
        assertThat(RecoverySuggestionPolicy.reasonCode("INSUFFICIENT", true)).isEmpty();
        assertThat(RecoverySuggestionPolicy.reasonCode(null, true)).isEmpty();
    }

    @Test
    void 일정이_많은_주는_위험_사유의_LOAD_HIGH로_본다() {
        assertThat(RecoverySuggestionPolicy.isOverloaded(List.of("DEADLINES", "LOAD_HIGH"))).isTrue();
        assertThat(RecoverySuggestionPolicy.isOverloaded(List.of("DEADLINES", "NO_REST_DAY"))).isFalse();
        assertThat(RecoverySuggestionPolicy.isOverloaded(List.of())).isFalse();
        assertThat(RecoverySuggestionPolicy.isOverloaded(null)).isFalse();
    }

    @Test
    void 활동은_운동_목록에서_날짜마다_돌아가며_고른다() {
        LocalDate day = LocalDate.of(2026, 11, 12);

        // 선호 활동이 없으면 목록 전체: 회복이 낮은 날은 가벼운 4가지, 일정이 많은 날은 5가지가 한 바퀴 돈다.
        assertThat(pickedOver(4, "LOW_RECOVERY", Set.of(), day))
                .containsExactlyInAnyOrder("WALK", "STRETCH", "YOGA", "MEDITATION");
        assertThat(pickedOver(5, "OVERLOAD", Set.of(), day))
                .containsExactlyInAnyOrder("WALK", "STRETCH", "YOGA", "MEDITATION", "LIGHT_RUN");
        assertThat(RecoverySuggestionPolicy.pickActivity("LOW_RECOVERY", Set.of(), day.plusDays(4)))
                .isEqualTo(RecoverySuggestionPolicy.pickActivity("LOW_RECOVERY", Set.of(), day));
    }

    @Test
    void 선호_활동이_목록에_있으면_그_안에서만_고르고_낮잠은_추천하지_않는다() {
        LocalDate day = LocalDate.of(2026, 11, 12);

        assertThat(pickedOver(2, "LOW_RECOVERY", Set.of("YOGA", "WALK", "NAP"), day))
                .containsExactlyInAnyOrder("WALK", "YOGA");
        assertThat(RecoverySuggestionPolicy.pickActivity("LOW_RECOVERY", Set.of("WALK"), day)).isEqualTo("WALK");
        // 선호가 낮잠뿐이거나, 회복이 낮은 날인데 선호가 가벼운 러닝뿐이면 목록 전체에서 고른다.
        assertThat(pickedOver(4, "LOW_RECOVERY", Set.of("NAP"), day)).doesNotContain("NAP").hasSize(4);
        assertThat(pickedOver(4, "LOW_RECOVERY", Set.of("LIGHT_RUN"), day)).doesNotContain("LIGHT_RUN");
        assertThat(RecoverySuggestionPolicy.pickActivity("OVERLOAD", Set.of("LIGHT_RUN"), day)).isEqualTo("LIGHT_RUN");
    }

    @Test
    void 제목과_문구는_이유와_활동으로_만든다() {
        assertThat(policy.title("WALK")).isEqualTo("20분 걷기");
        assertThat(policy.title("LIGHT_RUN")).isEqualTo("20분 가벼운 러닝");
        assertThat(policy.message("WALK", "LOW_RECOVERY", "NORMAL_LOW"))
                .isEqualTo("오늘 측정값이 지난주 평균보다 많이 낮아요. 20분 걷기 어때요? 숨이 살짝 찰 정도로 편하게 걸어 보세요.");
        assertThat(policy.message("MEDITATION", "LOW_RECOVERY", "OVERLOAD_LOW"))
                .isEqualTo("이번 주 일정이 평소보다 많고, 오늘 측정값이 지난주 평균보다 많이 낮아요. 20분 명상 어때요? "
                        + "편하게 앉아 천천히 숨을 고르며 쉬어 보세요.");
        assertThat(policy.message("STRETCH", "OVERLOAD", "BUILDING"))
                .isEqualTo("이번 주 일정이 평소보다 많아요. 가볍게 움직여 볼까요? 20분 스트레칭 어때요? "
                        + "목·어깨·허리를 천천히 늘려 긴장을 풀어 보세요.");
        assertThatThrownBy(() -> policy.title("NAP")).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> pickedOver(int days, String reasonCode, Set<String> preferred, LocalDate from) {
        return java.util.stream.IntStream.range(0, days)
                .mapToObj(i -> RecoverySuggestionPolicy.pickActivity(reasonCode, preferred, from.plusDays(i)))
                .toList();
    }
}
