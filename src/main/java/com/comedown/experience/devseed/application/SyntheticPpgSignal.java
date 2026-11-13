package com.comedown.experience.devseed.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 시연용 합성 카메라 밝기 신호. 실제 사용자 원신호 대신 쓰며 실제 측정 계산(hrv-cam-v1)을 그대로 거친다.
 * 박동 간격을 {@code 900 − x/2}, {@code 900 + x/2}ms로 번갈아 두면 연속 차이가 항상 x → 기대 RMSSD ≈ x(ms), 심박 약 67bpm.
 * 박동 시각마다 가우시안 맥파를 더하고(혈류가 늘면 어두워지므로 밝기에서 뺌), 프레임 간격 ±2ms 흔들림과 백색 잡음을 넣는다.
 */
final class SyntheticPpgSignal {

    static final double DURATION_MS = 70_000;
    static final double FPS = 30;
    static final double MEAN_IBI_MS = 900;
    static final double NOISE = 0.02;

    final double[] t;
    final double[] r;
    final double[] g;

    private SyntheticPpgSignal(double[] t, double[] r, double[] g) {
        this.t = t;
        this.r = r;
        this.g = g;
    }

    /** 같은 seed면 같은 신호(시드 결과 재현). */
    static SyntheticPpgSignal withRmssd(double rmssdMs, long seed) {
        double[] ibiPattern = {MEAN_IBI_MS - rmssdMs / 2, MEAN_IBI_MS + rmssdMs / 2};
        Random random = new Random(seed);
        List<Double> beats = new ArrayList<>();
        double beat = 300;
        for (int k = 0; beat < DURATION_MS + 1000; k++) {
            beats.add(beat);
            beat += ibiPattern[k % ibiPattern.length];
        }
        int n = (int) (DURATION_MS / 1000 * FPS);
        double[] t = new double[n];
        double[] r = new double[n];
        double[] g = new double[n];
        for (int i = 0; i < n; i++) {
            double ti = i * 1000.0 / FPS + (i == 0 ? 0 : random.nextDouble() * 4 - 2);
            double pulse = 0;
            for (double b : beats) {
                double d = (ti - (b + 150)) / 80.0;
                if (Math.abs(d) < 6) {
                    pulse += Math.exp(-0.5 * d * d);
                }
            }
            t[i] = ti;
            r[i] = 200 - 3.0 * pulse + NOISE * random.nextGaussian();
            g[i] = 40 - 1.0 * pulse + NOISE * random.nextGaussian();
        }
        return new SyntheticPpgSignal(t, r, g);
    }
}
