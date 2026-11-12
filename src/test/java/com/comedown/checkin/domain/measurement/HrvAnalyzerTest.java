package com.comedown.checkin.domain.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 합성 PPG 신호로 참고 구현(HrvAnalyzer)의 기본 동작을 확인한다.
 * 실제 휴대폰 측정 데이터로 임계값을 조정하는 검증은 별도로 필요하다.
 */
class HrvAnalyzerTest {

    /** 박동 간격을 880ms / 920ms로 번갈아 두면 연속 차이가 항상 40ms → 기대 RMSSD = 40ms. */
    private static final double[] ALTERNATING_IBI_MS = {880, 920};

    @Test
    void 깨끗한_합성_신호는_RMSSD를_복원하고_GOOD() {
        Signal s = synthetic(70_000, 30, ALTERNATING_IBI_MS, 0.02, 1);

        HrvAnalyzer.Result result = HrvAnalyzer.analyze(s.t, s.r, s.g);

        assertThat(result.ok()).isTrue();
        assertThat(result.retryReason()).isNull();
        assertThat(result.quality()).isEqualTo("GOOD");
        assertThat(result.rmssdMs()).isCloseTo(40.0, within(6.0));
        assertThat(result.lnRmssd()).isCloseTo(Math.log(result.rmssdMs()), within(1e-9));
        assertThat(result.hrBpm()).isCloseTo(60_000.0 / 900, within(3.0));
        assertThat(result.pairCount()).isGreaterThanOrEqualTo(HrvAnalyzer.MIN_PAIRS);
    }

    @Test
    void 준비_10초를_빼고_50초가_안_되면_TOO_SHORT() {
        Signal s = synthetic(55_000, 30, ALTERNATING_IBI_MS, 0.02, 2);

        HrvAnalyzer.Result result = HrvAnalyzer.analyze(s.t, s.r, s.g);

        assertThat(result.ok()).isFalse();
        assertThat(result.quality()).isEqualTo("RETRY");
        assertThat(result.retryReason()).isEqualTo("TOO_SHORT");
        assertThat(result.rmssdMs()).isNull();
    }

    @Test
    void 초당_20프레임_미만이면_LOW_FPS() {
        Signal s = synthetic(70_000, 15, ALTERNATING_IBI_MS, 0.02, 3);

        HrvAnalyzer.Result result = HrvAnalyzer.analyze(s.t, s.r, s.g);

        assertThat(result.ok()).isFalse();
        assertThat(result.retryReason()).isEqualTo("LOW_FPS");
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

    // ---- 합성 신호 ----

    private record Signal(double[] t, double[] r, double[] g) {
    }

    /**
     * 박동 시각마다 가우시안 맥파를 더한 PPG 신호. 혈류가 늘면 어두워지므로 밝기에서 뺀다.
     * 프레임 간격에 ±2ms 흔들림과 백색 잡음을 넣는다.
     */
    private static Signal synthetic(double totalMs, double fps, double[] ibiPattern, double noise, long seed) {
        Random random = new Random(seed);
        List<Double> beats = new ArrayList<>();
        double beat = 300;
        for (int k = 0; beat < totalMs + 1000; k++) {
            beats.add(beat);
            beat += ibiPattern[k % ibiPattern.length];
        }
        int n = (int) (totalMs / 1000 * fps);
        double[] t = new double[n];
        double[] r = new double[n];
        double[] g = new double[n];
        for (int i = 0; i < n; i++) {
            double ti = i * 1000.0 / fps + (i == 0 ? 0 : random.nextDouble() * 4 - 2);
            double pulse = 0;
            for (double b : beats) {
                double d = (ti - (b + 150)) / 80.0;
                if (Math.abs(d) < 6) {
                    pulse += Math.exp(-0.5 * d * d);
                }
            }
            t[i] = ti;
            r[i] = 200 - 3.0 * pulse + noise * random.nextGaussian();
            g[i] = 40 - 1.0 * pulse + noise * random.nextGaussian();
        }
        return new Signal(t, r, g);
    }
}
