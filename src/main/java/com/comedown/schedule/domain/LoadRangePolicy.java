package com.comedown.schedule.domain;

import com.comedown.schedule.contract.ProgramWeekLoadRange;
import java.util.List;
import java.util.Optional;

/**
 * 내 범위 중 현재 위치(계산 정책 6절, 일정 부하 쪽). 온보딩 기준 n주차 부하 점수로 계산한다.
 * <ul>
 *   <li>표본 수: {@link ProgramWeekLoadRange#MIN_SAMPLES}개 이상(일차 조건은 호출하는 쪽)</li>
 *   <li>최저·최고 = 하위 5%·상위 5% 백분위(선형 보간, 회복 쪽 RangePositionPolicy와 같은 방식)</li>
 *   <li>위치 = (현재 − 최저) ÷ (최고 − 최저) × 100, 0~100으로 제한</li>
 *   <li>최저 = 최고면 계산하지 않는다(INSUFFICIENT_DATA)</li>
 * </ul>
 */
public final class LoadRangePolicy {

    static final double LOW_PERCENTILE = 5;
    static final double HIGH_PERCENTILE = 95;

    private LoadRangePolicy() {
    }

    /**
     * @param samples 표본 부하 점수(현재 주차 포함, 순서 무관)
     * @param current 진행 중인 주차의 부하 점수
     * @return 표본이 부족하거나 최저 = 최고면 비어 있음
     */
    public static Optional<Position> evaluate(List<Double> samples, double current) {
        if (samples.size() < ProgramWeekLoadRange.MIN_SAMPLES) {
            return Optional.empty();
        }
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double low = percentile(sorted, LOW_PERCENTILE);
        double high = percentile(sorted, HIGH_PERCENTILE);
        if (!(high > low)) {
            return Optional.empty();
        }
        double position = Math.max(0, Math.min(100, (current - low) / (high - low) * 100));
        return Optional.of(new Position(position, low, high));
    }

    /** 정렬된 배열의 p 백분위(선형 보간). */
    static double percentile(double[] sorted, double p) {
        double pos = (p / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    /** 부하 점수 단위 최저·최고와 0~100 위치. */
    public record Position(double position, double low, double high) {
    }
}
