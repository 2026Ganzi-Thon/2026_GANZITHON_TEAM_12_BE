package com.comedown.checkin.domain.measurement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * 카메라 밝기 신호(t, r, g 배열)로 HRV(RMSSD)를 계산한다.
 *
 * 처리 순서
 *  1. 앞 10초 제거, 길이·프레임 수 검사
 *  2. 불규칙한 타임스탬프를 30Hz 균일 간격으로 변환
 *  3. 밝기 반전 + 0.7~3.5Hz 대역 통과 필터 (양방향, 시간 지연 없음)
 *  4. 자기상관으로 대략적인 심박 주기 추정
 *  5. 박동 꼭짓점 검출 + 포물선 보간으로 정밀 위치 계산
 *  6. 박동 간격(IBI) 계산 후 이상 간격 제거 (범위, 주변 중앙값, 박동 모양)
 *  7. 연속으로 유효한 간격 쌍만으로 RMSSD 계산
 *  8. 품질 판정, R·G 채널 중 더 좋은 쪽 선택
 *
 * 모든 임계값은 합성 신호로 정한 초기값이며, 실제 휴대폰 측정 데이터로 조정해야 한다.
 *
 * 출처: 팀장 전달 참고 구현(백엔드 설계 수정 요청 1, 1-1). 원신호(t, r, g)는 계산에만 쓰고 저장하지 않는다.
 */
public final class HrvAnalyzer {

    /** hrv_measurements.algorithm_version. 임계값·처리 순서를 바꾸면 버전을 올린다. */
    public static final String ALGORITHM_VERSION = "hrv-cam-v1";

    // 1. 측정 구간
    static final double WARMUP_MS = 10_000;
    static final double MIN_DURATION_MS = 50_000;
    static final double MIN_FPS = 20;

    // 2~4. 신호 처리
    static final double FS = 30.0;
    static final double BAND_LOW_HZ = 0.7;
    static final double BAND_HIGH_HZ = 3.5;
    static final double NOISE_HIGHPASS_HZ = 6.0;
    static final double MIN_BPM = 40;
    static final double MAX_BPM = 180;
    static final double MIN_PEAK_DISTANCE_SEC = 0.33;

    // 6. 이상 간격 제거
    static final double MIN_IBI_MS = 333;
    static final double MAX_IBI_MS = 1500;
    static final double LOCAL_MEDIAN_TOLERANCE = 0.20;
    static final double TEMPLATE_CORR_MIN = 0.90;

    // 8. 품질
    static final int MIN_PAIRS = 30;
    static final double MIN_VALID_RATIO = 0.70;
    static final double GOOD_VALID_RATIO = 0.90;
    static final double MAX_NOISE_RATIO = 0.12;
    static final double GOOD_NOISE_RATIO = 0.06;
    static final double MIN_PERIODICITY = 0.50;

    private HrvAnalyzer() {
    }

    public record Result(
            boolean ok,
            String retryReason,   // NO_PULSE, TOO_NOISY, TOO_SHORT, LOW_FPS (ok면 null)
            String quality,       // GOOD, FAIR, RETRY
            String channel,       // R, G
            Double rmssdMs,
            Double lnRmssd,
            Double hrBpm,
            int validIbiCount,
            int totalIbiCount,
            int pairCount,
            double validRatio,
            double noiseRatio,
            double periodicity) {
    }

    /**
     * @param t 프레임 타임스탬프(ms, 측정 시작 = 0)
     * @param r 프레임별 빨강 채널 평균 밝기
     * @param g 프레임별 초록 채널 평균 밝기
     */
    public static Result analyze(double[] t, double[] r, double[] g) {
        if (t == null || r == null || g == null || t.length != r.length || t.length != g.length) {
            throw new IllegalArgumentException("t, r, g 배열의 길이가 같아야 합니다");
        }

        // 1. 앞 10초 제거 + 시간이 거꾸로 가거나 중복된 프레임 제거
        List<Integer> keep = new ArrayList<>();
        double last = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < t.length; i++) {
            if (t[i] >= WARMUP_MS && t[i] > last) {
                keep.add(i);
                last = t[i];
            }
        }
        int n = keep.size();
        if (n < 2) {
            return retry("TOO_SHORT", null, Double.NaN, Double.NaN);
        }
        double[] tt = new double[n];
        double[] rr = new double[n];
        double[] gg = new double[n];
        for (int k = 0; k < n; k++) {
            int i = keep.get(k);
            tt[k] = t[i];
            rr[k] = r[i];
            gg[k] = g[i];
        }

        double duration = tt[n - 1] - tt[0];
        if (duration < MIN_DURATION_MS) {
            return retry("TOO_SHORT", null, Double.NaN, Double.NaN);
        }
        double fps = (n - 1) / (duration / 1000.0);
        if (fps < MIN_FPS) {
            return retry("LOW_FPS", null, Double.NaN, Double.NaN);
        }

        // 8. 두 채널을 모두 계산하고 더 좋은 쪽 선택
        Result red = analyzeChannel(tt, rr, "R");
        Result green = analyzeChannel(tt, gg, "G");
        return better(red, green);
    }

    static Result analyzeChannel(double[] t, double[] x, String channel) {
        // 2. 30Hz 균일 간격으로 변환
        double[] u = resample(t, x, FS);

        // 3. 반전 + 평균 제거 + 대역 통과 필터
        //    피가 많이 흐를수록 어두워지므로, 뒤집어야 박동이 꼭짓점이 된다.
        double mean = mean(u);
        double[] inverted = new double[u.length];
        for (int i = 0; i < u.length; i++) {
            inverted[i] = mean - u[i];
        }
        double[] y = Biquad.lowpass(FS, BAND_HIGH_HZ)
                .filtfilt(Biquad.highpass(FS, BAND_LOW_HZ).filtfilt(inverted));

        // 잡음비 = 6Hz 이상 고주파(맥박 성분이 거의 없는 대역) 크기 ÷ 박동 진폭
        //    짧은 움직임에 휘둘리지 않도록 표준편차 대신 MAD 기반 추정값 사용
        double[] hf = Biquad.highpass(FS, NOISE_HIGHPASS_HZ).filtfilt(u);
        double amp = percentile(y, 95) - percentile(y, 5);
        double noiseRatio = amp > 0 ? robustStd(hf) / amp : Double.POSITIVE_INFINITY;

        // 4. 자기상관으로 심박 주기 추정
        //    10초 구간별로 계산한 뒤 중앙값을 써서, 몇 초간의 움직임이 전체 판단을 망치지 않게 함
        int minLag = (int) Math.floor(FS * 60 / MAX_BPM);
        int maxLag = (int) Math.ceil(FS * 60 / MIN_BPM);
        int win = (int) (10 * FS);
        int hop = (int) (5 * FS);
        List<double[]> windows = new ArrayList<>();
        for (int start = 0; start + win <= y.length; start += hop) {
            windows.add(Arrays.copyOfRange(y, start, start + win));
        }
        if (windows.isEmpty()) {
            windows.add(y);
        }
        double[] ac = new double[maxLag - minLag + 1];
        double acMax = Double.NEGATIVE_INFINITY;
        double[] perWindow = new double[windows.size()];
        for (int lag = minLag; lag <= maxLag; lag++) {
            for (int w = 0; w < windows.size(); w++) {
                perWindow[w] = lagCorrelation(windows.get(w), lag);
            }
            ac[lag - minLag] = median(perWindow);
            acMax = Math.max(acMax, ac[lag - minLag]);
        }
        if (!(acMax >= MIN_PERIODICITY)) {
            return retry("NO_PULSE", channel, noiseRatio, acMax);
        }
        int period = maxLag;
        for (int lag = minLag; lag <= maxLag; lag++) {
            if (ac[lag - minLag] >= 0.8 * acMax) {
                period = lag;
                break;
            }
        }

        // 5. 박동 꼭짓점 검출 (한 박동 안의 작은 봉우리를 박동으로 오인하지 않도록 최소 간격 적용)
        int minDistance = Math.max((int) Math.round(MIN_PEAK_DISTANCE_SEC * FS), (int) Math.round(0.6 * period));
        int[] peaks = detectPeaks(y, minDistance);
        if (peaks.length < 3) {
            return retry("NO_PULSE", channel, noiseRatio, acMax);
        }

        // 6-1. 박동 모양 검사: 평균 박동 모양과 닮지 않은 박동(움직임 등)은 제외
        boolean[] goodBeat = templateCheck(y, peaks, (int) Math.round(0.3 * period));

        // 5-1. 포물선 보간으로 박동 시각을 33ms보다 정밀하게 계산
        double[] beatMs = new double[peaks.length];
        for (int k = 0; k < peaks.length; k++) {
            beatMs[k] = t[0] + refinePeak(y, peaks[k]) * 1000.0 / FS;
        }

        // 6-2. 박동 간격 + 이상 간격 제거
        int m = peaks.length - 1;
        double[] ibi = new double[m];
        boolean[] inRange = new boolean[m];
        for (int k = 0; k < m; k++) {
            ibi[k] = beatMs[k + 1] - beatMs[k];
            inRange[k] = ibi[k] >= MIN_IBI_MS && ibi[k] <= MAX_IBI_MS;
        }
        boolean[] valid = new boolean[m];
        int validCount = 0;
        List<Double> validIbis = new ArrayList<>();
        for (int k = 0; k < m; k++) {
            if (!inRange[k] || !goodBeat[k] || !goodBeat[k + 1]) {
                continue;
            }
            List<Double> window = new ArrayList<>();
            for (int j = Math.max(0, k - 2); j <= Math.min(m - 1, k + 2); j++) {
                if (inRange[j]) {
                    window.add(ibi[j]);
                }
            }
            double med = median(window);
            if (Math.abs(ibi[k] - med) <= LOCAL_MEDIAN_TOLERANCE * med) {
                valid[k] = true;
                validCount++;
                validIbis.add(ibi[k]);
            }
        }

        // 7. RMSSD: 연속으로 둘 다 유효한 간격 쌍만 사용
        double sumSq = 0;
        int pairs = 0;
        for (int k = 1; k < m; k++) {
            if (valid[k] && valid[k - 1]) {
                double d = ibi[k] - ibi[k - 1];
                sumSq += d * d;
                pairs++;
            }
        }
        double validRatio = m > 0 ? (double) validCount / m : 0;

        // 8. 품질 판정
        if (pairs < MIN_PAIRS || validRatio < MIN_VALID_RATIO || noiseRatio > MAX_NOISE_RATIO) {
            return new Result(false, "TOO_NOISY", "RETRY", channel, null, null, null,
                    validCount, m, pairs, validRatio, noiseRatio, acMax);
        }
        double rmssd = Math.sqrt(sumSq / pairs);
        double hr = 60_000.0 / median(validIbis);
        String quality = (validRatio >= GOOD_VALID_RATIO && noiseRatio <= GOOD_NOISE_RATIO) ? "GOOD" : "FAIR";
        return new Result(true, null, quality, channel, rmssd, Math.log(rmssd), hr,
                validCount, m, pairs, validRatio, noiseRatio, acMax);
    }

    // ---------- 채널 선택 ----------

    static Result better(Result a, Result b) {
        if (a.ok() != b.ok()) {
            return a.ok() ? a : b;
        }
        double na = Double.isNaN(a.noiseRatio()) ? Double.POSITIVE_INFINITY : a.noiseRatio();
        double nb = Double.isNaN(b.noiseRatio()) ? Double.POSITIVE_INFINITY : b.noiseRatio();
        return na <= nb ? a : b;
    }

    static Result retry(String reason, String channel, double noiseRatio, double periodicity) {
        return new Result(false, reason, "RETRY", channel, null, null, null,
                0, 0, 0, 0, noiseRatio, periodicity);
    }

    // ---------- 신호 처리 도구 ----------

    /** 불규칙한 시각의 샘플을 일정한 간격(fs Hz)으로 선형 보간한다. */
    static double[] resample(double[] t, double[] x, double fs) {
        double step = 1000.0 / fs;
        int count = (int) Math.floor((t[t.length - 1] - t[0]) / step) + 1;
        double[] out = new double[count];
        int j = 0;
        for (int i = 0; i < count; i++) {
            double ti = t[0] + i * step;
            while (j < t.length - 2 && t[j + 1] < ti) {
                j++;
            }
            double span = t[j + 1] - t[j];
            double w = span > 0 ? (ti - t[j]) / span : 0;
            w = Math.max(0, Math.min(1, w));
            out[i] = x[j] + w * (x[j + 1] - x[j]);
        }
        return out;
    }

    /** 0보다 큰 꼭짓점 중 높은 것부터 고르되, 이미 고른 꼭짓점과 minDistance 이내면 버린다. */
    static int[] detectPeaks(double[] y, int minDistance) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < y.length - 1; i++) {
            if (y[i] > y[i - 1] && y[i] >= y[i + 1] && y[i] > 0) {
                candidates.add(i);
            }
        }
        candidates.sort((p, q) -> Double.compare(y[q], y[p]));
        TreeSet<Integer> accepted = new TreeSet<>();
        for (int i : candidates) {
            Integer lower = accepted.floor(i);
            Integer higher = accepted.ceiling(i);
            boolean tooClose = (lower != null && i - lower < minDistance)
                    || (higher != null && higher - i < minDistance);
            if (!tooClose) {
                accepted.add(i);
            }
        }
        return accepted.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 꼭짓점과 앞뒤 두 점으로 포물선을 그려 실제 꼭짓점 위치(소수점 샘플 단위)를 추정한다. */
    static double refinePeak(double[] y, int i) {
        if (i <= 0 || i >= y.length - 1) {
            return i;
        }
        double y0 = y[i - 1];
        double y1 = y[i];
        double y2 = y[i + 1];
        double den = y0 - 2 * y1 + y2;
        if (den == 0) {
            return i;
        }
        double delta = 0.5 * (y0 - y2) / den;
        return i + Math.max(-0.5, Math.min(0.5, delta));
    }

    /** 각 박동 주변 구간을 중앙값 박동 모양과 비교해, 상관이 낮으면 나쁜 박동으로 표시한다. */
    static boolean[] templateCheck(double[] y, int[] peaks, int half) {
        boolean[] good = new boolean[peaks.length];
        List<double[]> segments = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        for (int k = 0; k < peaks.length; k++) {
            int start = peaks[k] - half;
            int end = peaks[k] + half;
            if (start >= 0 && end < y.length) {
                segments.add(Arrays.copyOfRange(y, start, end + 1));
                owners.add(k);
            }
        }
        if (segments.size() < 5) {
            return good;
        }
        int len = 2 * half + 1;
        double[] template = new double[len];
        double[] column = new double[segments.size()];
        for (int s = 0; s < len; s++) {
            for (int k = 0; k < segments.size(); k++) {
                column[k] = segments.get(k)[s];
            }
            template[s] = median(column);
        }
        for (int k = 0; k < segments.size(); k++) {
            good[owners.get(k)] = correlation(segments.get(k), template) >= TEMPLATE_CORR_MIN;
        }
        return good;
    }

    static double lagCorrelation(double[] y, int lag) {
        if (lag >= y.length - 2) {
            return Double.NaN;
        }
        return correlation(Arrays.copyOfRange(y, 0, y.length - lag), Arrays.copyOfRange(y, lag, y.length));
    }

    static double correlation(double[] a, double[] b) {
        double ma = mean(a);
        double mb = mean(b);
        double num = 0;
        double da = 0;
        double db = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i] - ma;
            double z = b[i] - mb;
            num += x * z;
            da += x * x;
            db += z * z;
        }
        return (da == 0 || db == 0) ? 0 : num / Math.sqrt(da * db);
    }

    static double mean(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return s / x.length;
    }

    static double median(double[] x) {
        double[] c = x.clone();
        Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }

    static double median(List<Double> x) {
        return median(x.stream().mapToDouble(Double::doubleValue).toArray());
    }

    static double percentile(double[] x, double p) {
        double[] c = x.clone();
        Arrays.sort(c);
        double pos = (p / 100.0) * (c.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return c[lo] + (pos - lo) * (c[hi] - c[lo]);
    }

    /** 중앙값 절대편차(MAD) × 1.4826: 짧은 튀는 값에 덜 민감한 표준편차 추정치 */
    static double robustStd(double[] x) {
        double med = median(x);
        double[] dev = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            dev[i] = Math.abs(x[i] - med);
        }
        return 1.4826 * median(dev);
    }

    // ---------- 2차 필터 ----------

    /** 2차 IIR 필터(RBJ 공식). filtfilt는 정방향 + 역방향으로 두 번 적용해 시간 지연을 없앤다. */
    static final class Biquad {
        private final double b0;
        private final double b1;
        private final double b2;
        private final double a1;
        private final double a2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0;
            this.b1 = b1 / a0;
            this.b2 = b2 / a0;
            this.a1 = a1 / a0;
            this.a2 = a2 / a0;
        }

        static Biquad lowpass(double fs, double fc) {
            double w = 2 * Math.PI * fc / fs;
            double alpha = Math.sin(w) / (2 * Math.sqrt(0.5));
            double cos = Math.cos(w);
            return new Biquad((1 - cos) / 2, 1 - cos, (1 - cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
        }

        static Biquad highpass(double fs, double fc) {
            double w = 2 * Math.PI * fc / fs;
            double alpha = Math.sin(w) / (2 * Math.sqrt(0.5));
            double cos = Math.cos(w);
            return new Biquad((1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
        }

        double[] filter(double[] x) {
            double[] y = new double[x.length];
            double x1 = 0;
            double x2 = 0;
            double y1 = 0;
            double y2 = 0;
            for (int i = 0; i < x.length; i++) {
                double out = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1;
                x1 = x[i];
                y2 = y1;
                y1 = out;
                y[i] = out;
            }
            return y;
        }

        /** 양 끝을 뒤집어 붙여(odd reflection) 시작·끝의 출렁임을 줄인 뒤 양방향 필터링 */
        double[] filtfilt(double[] x) {
            int n = x.length;
            int pad = Math.min(n - 1, 90);
            double[] ext = new double[n + 2 * pad];
            for (int i = 0; i < pad; i++) {
                ext[i] = 2 * x[0] - x[pad - i];
                ext[n + pad + i] = 2 * x[n - 1] - x[n - 2 - i];
            }
            System.arraycopy(x, 0, ext, pad, n);
            double[] forward = filter(ext);
            reverse(forward);
            double[] backward = filter(forward);
            reverse(backward);
            return Arrays.copyOfRange(backward, pad, pad + n);
        }

        private static void reverse(double[] a) {
            for (int i = 0, j = a.length - 1; i < j; i++, j--) {
                double tmp = a[i];
                a[i] = a[j];
                a[j] = tmp;
            }
        }
    }
}
