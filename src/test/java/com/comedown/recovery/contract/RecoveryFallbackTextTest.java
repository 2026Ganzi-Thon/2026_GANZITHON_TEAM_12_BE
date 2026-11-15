package com.comedown.recovery.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RecoveryFallbackTextTest {

    @Test
    void 낮음이면_평소_범위보다_낮다고_하고_변화율을_붙인다() {
        assertThat(RecoveryFallbackText.of("LOW", new BigDecimal("-12.0")))
                .isEqualTo("최근 7일 회복이 평소 범위보다 낮아요. 평소보다 12% 낮아요.");
    }

    @Test
    void 평소_범위면_변화율이_0일_때_비슷하다고_한다() {
        assertThat(RecoveryFallbackText.of("NORMAL", new BigDecimal("0.0")))
                .isEqualTo("최근 7일 회복이 평소 범위 안에 있어요. 평소와 비슷해요.");
        assertThat(RecoveryFallbackText.of("NORMAL", new BigDecimal("3.5")))
                .isEqualTo("최근 7일 회복이 평소 범위 안에 있어요. 평소보다 3.5% 높아요.");
    }

    @Test
    void 측정_부족이고_변화율이_없으면_상태_문장만_준다() {
        assertThat(RecoveryFallbackText.of("INSUFFICIENT", null))
                .isEqualTo("최근 7일 측정이 적어 평소와 비교하기 어려워요.");
    }
}
