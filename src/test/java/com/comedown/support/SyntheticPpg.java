package com.comedown.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 합성 PPG(카메라 밝기) 신호. 박동 시각마다 가우시안 맥파를 더하고, 혈류가 늘면 어두워지므로 밝기에서 뺀다.
 * 프레임 간격에 ±2ms 흔들림과 백색 잡음을 넣는다. 실제 사용자 원신호 대신 테스트에 쓴다.
 */
public final class SyntheticPpg {

    /** 박동 간격 880ms / 920ms 반복 → 연속 차이가 항상 40ms, 기대 RMSSD = 40ms. */
    public static final double[] ALTERNATING_IBI_MS = {880, 920};

    public final double[] t;
    public final double[] r;
    public final double[] g;

    private SyntheticPpg(double[] t, double[] r, double[] g) {
        this.t = t;
        this.r = r;
        this.g = g;
    }

    /** 기대 RMSSD 40ms인 깨끗한 신호. */
    public static SyntheticPpg clean(double totalMs, double fps) {
        return of(totalMs, fps, ALTERNATING_IBI_MS, 0.02, 1);
    }

    public static SyntheticPpg of(double totalMs, double fps, double[] ibiPattern, double noise, long seed) {
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
        return new SyntheticPpg(t, r, g);
    }
}
