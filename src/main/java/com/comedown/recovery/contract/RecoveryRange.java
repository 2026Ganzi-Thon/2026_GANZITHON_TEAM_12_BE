package com.comedown.recovery.contract;

/**
 * 회복의 내 범위 중 현재 위치(계산 정책 6절). 공개 여부(28일차 이후)는 호출하는 쪽이 판단한다.
 *
 * @param trendSampleCount 지금까지의 7일 추세 값 개수(20개 이상이어야 공개)
 * @param position         0~100. 표본 부족·최저 = 최고면 null
 * @param lowLn            하위 5% 백분위(ln). 계산하지 못하면 null
 * @param highLn           상위 5% 백분위(ln). 계산하지 못하면 null
 */
public record RecoveryRange(int trendSampleCount, Double position, Double lowLn, Double highLn) {

    /** 공개에 필요한 7일 추세 값 개수(계산 정책 6절). */
    public static final int MIN_SAMPLES = 20;

    public boolean enoughSamples() {
        return trendSampleCount >= MIN_SAMPLES;
    }
}
