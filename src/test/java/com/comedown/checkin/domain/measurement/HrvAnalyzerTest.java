package com.comedown.checkin.domain.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.comedown.support.SyntheticPpg;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 합성 PPG 신호로 참고 구현(HrvAnalyzer)의 기본 동작을 확인한다.
 * 실제 휴대폰 측정 데이터로 임계값을 조정하는 검증은 별도로 필요하다.
 */
class HrvAnalyzerTest {

    /** 박동 간격을 880ms / 920ms로 번갈아 두면 연속 차이가 항상 40ms → 기대 RMSSD = 40ms. */
    private static final double[] ALTERNATING_IBI_MS = SyntheticPpg.ALTERNATING_IBI_MS;

    @Test
    void 깨끗한_합성_신호는_RMSSD를_복원하고_GOOD() {
        SyntheticPpg s = SyntheticPpg.of(70_000, 30, ALTERNATING_IBI_MS, 0.02, 1);

        HrvAnalyzer.Result result = HrvAnalyzer.analyze(s.t, s.r, s.g);

        assertThat(result.ok()).isTrue();
        assertThat(result.retryReason()).isNull();
        assertThat(result.quality()).isEqualTo("GOOD");
        assertThat(result.rmssdMs()).isCloseTo(40.0, within(6.0));
        assertThat(result.lnRmssd()).isCloseTo(Math.log(result.rmssdMs()), within(1e-9));
        assertThat(result.hrBpm()).isCloseTo(60_000.0 / 900, within(3.0));
        assertThat(result.pairCount()).isGreaterThanOrEqualTo(HrvAnalyzer.MIN_PAIRS);
        // 저장용 분석 구간 정보: 앞 10초 제거 후 약 60초 · 30fps
        assertThat(result.frameCount()).isBetween(1795, 1800);
        assertThat(result.durationSec()).isCloseTo(60.0, within(0.1));
        assertThat(result.fps()).isCloseTo(30.0, within(0.2));
    }

    @Test
    void 준비_10초를_빼고_50초가_안_되면_TOO_SHORT() {
        SyntheticPpg s = SyntheticPpg.of(55_000, 30, ALTERNATING_IBI_MS, 0.02, 2);

        HrvAnalyzer.Result result = HrvAnalyzer.analyze(s.t, s.r, s.g);

        assertThat(result.ok()).isFalse();
        assertThat(result.quality()).isEqualTo("RETRY");
        assertThat(result.retryReason()).isEqualTo("TOO_SHORT");
        assertThat(result.rmssdMs()).isNull();
    }

    @Test
    void 초당_20프레임_미만이면_LOW_FPS() {
        SyntheticPpg s = SyntheticPpg.of(70_000, 15, ALTERNATING_IBI_MS, 0.02, 3);

        HrvAnalyzer.Result result = HrvAnalyzer.analyze(s.t, s.r, s.g);

        assertThat(result.ok()).isFalse();
        assertThat(result.retryReason()).isEqualTo("LOW_FPS");
        assertThat(result.fps()).isCloseTo(15.0, within(0.2));
        assertThat(result.frameCount()).isPositive();
    }

    @Test
    void 준비_10초_안에서_끝나면_프레임_정보는_비어_있다() {
        SyntheticPpg s = SyntheticPpg.of(9_000, 30, ALTERNATING_IBI_MS, 0.02, 5);

        HrvAnalyzer.Result result = HrvAnalyzer.analyze(s.t, s.r, s.g);

        assertThat(result.retryReason()).isEqualTo("TOO_SHORT");
        assertThat(result.frameCount()).isZero();
        assertThat(result.durationSec()).isNull();
        assertThat(result.fps()).isNull();
    }

    @Test
    void 맥박_없는_잡음은_재측정() {
        Random random = new Random(4);
        int n = 70 * 30;
        double[] t = new double[n];
        double[] r = new double[n];
        double[] g = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 1000.0 / 30;
            r[i] = 200 + random.nextGaussian();
            g[i] = 40 + random.nextGaussian();
        }

        HrvAnalyzer.Result result = HrvAnalyzer.analyze(t, r, g);

        assertThat(result.ok()).isFalse();
        assertThat(result.quality()).isEqualTo("RETRY");
        assertThat(result.retryReason()).isIn("NO_PULSE", "TOO_NOISY");
        assertThat(result.rmssdMs()).isNull();
    }

    @Test
    void 배열_길이가_다르면_예외() {
        assertThatThrownBy(() -> HrvAnalyzer.analyze(new double[3], new double[3], new double[2]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
