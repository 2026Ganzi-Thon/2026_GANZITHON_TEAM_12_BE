package com.comedown.cbi.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 계산 정책 2절 CBI 채점(cbi-ko-2026.11.2). */
class CbiQuestionnaireTest {

    private static Map<Integer, Integer> all(int option) {
        Map<Integer, Integer> options = new HashMap<>();
        for (int no = 1; no <= 13; no++) {
            options.put(no, option);
        }
        return options;
    }

    @Test
    void 문항은_13개이고_7_8_9번만_정도형이며_13번만_역채점() {
        assertThat(CbiQuestionnaire.ITEMS).hasSize(13);
        assertThat(CbiQuestionnaire.ITEMS).filteredOn(i -> i.scale() == CbiQuestionnaire.Scale.DEGREE)
                .extracting(CbiQuestionnaire.Item::no).containsExactly(7, 8, 9);
        assertThat(CbiQuestionnaire.ITEMS).filteredOn(CbiQuestionnaire.Item::reverse)
                .extracting(CbiQuestionnaire.Item::no).containsExactly(13);
        assertThat(CbiQuestionnaire.ITEMS).filteredOn(i -> i.subscale() == CbiQuestionnaire.Subscale.PERSONAL)
                .extracting(CbiQuestionnaire.Item::no).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void 보기_0에서_4는_100_75_50_25_0점이고_역문항은_뒤집는다() {
        assertThat(new int[] {
                CbiQuestionnaire.value(1, 0), CbiQuestionnaire.value(1, 1), CbiQuestionnaire.value(1, 2),
                CbiQuestionnaire.value(1, 3), CbiQuestionnaire.value(1, 4)})
                .containsExactly(100, 75, 50, 25, 0);
        assertThat(CbiQuestionnaire.value(13, 0)).isZero();
        assertThat(CbiQuestionnaire.value(13, 4)).isEqualTo(100);
    }

    @Test
    void 하위척도_점수는_응답_점수_평균이고_소수_둘째_자리() {
        Map<Integer, Integer> options = all(2);
        // PERSONAL 1~6: 75,75,50,50,75,50 → 62.5
        options.put(1, 1);
        options.put(2, 1);
        options.put(5, 1);
        // WORK 7~13: 75,50,50,50,50,50 + 13번(보기 2 → 역채점 50) → 375 / 7 = 53.571…
        options.put(7, 1);

        CbiQuestionnaire.Score score = CbiQuestionnaire.score(options);

        assertThat(score.personal()).isEqualByComparingTo("62.50");
        assertThat(score.work()).isEqualByComparingTo("53.57");
        assertThat(score.highRisk()).isFalse();
    }

    @Test
    void 어느_하위척도든_75_이상이면_high_risk() {
        // 모두 보기 0: PERSONAL 100, WORK (600 + 13번 역채점 0) / 7 = 85.71
        CbiQuestionnaire.Score worst = CbiQuestionnaire.score(all(0));
        assertThat(worst.personal()).isEqualByComparingTo("100.00");
        assertThat(worst.work()).isEqualByComparingTo("85.71");
        assertThat(worst.highRisk()).isTrue();

        // 모두 보기 4: PERSONAL 0, WORK (0 + 13번 역채점 100) / 7 = 14.29
        CbiQuestionnaire.Score best = CbiQuestionnaire.score(all(4));
        assertThat(best.personal()).isEqualByComparingTo("0.00");
        assertThat(best.work()).isEqualByComparingTo("14.29");
        assertThat(best.highRisk()).isFalse();
    }

    @Test
    void 표시_수준은_50과_75를_경계로_나눈다() {
        assertThat(CbiQuestionnaire.level(new BigDecimal("49.99"))).isEqualTo(CbiQuestionnaire.Level.LOW);
        assertThat(CbiQuestionnaire.level(new BigDecimal("50.00"))).isEqualTo(CbiQuestionnaire.Level.HIGH);
        assertThat(CbiQuestionnaire.level(new BigDecimal("74.99"))).isEqualTo(CbiQuestionnaire.Level.HIGH);
        assertThat(CbiQuestionnaire.level(new BigDecimal("75.00"))).isEqualTo(CbiQuestionnaire.Level.VERY_HIGH);
    }

    @Test
    void 답이_빠졌거나_보기가_범위를_벗어나면_거부() {
        Map<Integer, Integer> missing = all(1);
        missing.remove(13);
        assertThatThrownBy(() -> CbiQuestionnaire.score(missing)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CbiQuestionnaire.value(1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CbiQuestionnaire.item(14)).isInstanceOf(IllegalArgumentException.class);
    }
}
