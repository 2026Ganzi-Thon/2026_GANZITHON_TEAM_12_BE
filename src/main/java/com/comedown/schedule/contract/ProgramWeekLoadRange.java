package com.comedown.schedule.contract;

/**
 * 일정 부하의 내 범위 중 현재 위치(계산 정책 6절, {@link ProgramWeekLoadQuery#range}). 공개 여부(28일차 이후)는
 * 호출하는 쪽이 판단한다.
 *
 * @param sampleCount 표본 수: 1주차~진행 중인 주차의 부하 점수 중 지난 주차는 집계 대상 일정이 있던 주차만,
 *                    진행 중인 주차는 항상 센다
 * @param position    0~100. 표본 부족·최저 = 최고면 null
 * @param low         하위 5% 백분위(부하 점수). 계산하지 못하면 null
 * @param high        상위 5% 백분위(부하 점수). 계산하지 못하면 null
 */
public record ProgramWeekLoadRange(int sampleCount, Double position, Double low, Double high) {

    /** 공개에 필요한 표본(주차) 수. 28일차의 1~4주차(2026-11-19 결정, 계산 정책 6절). */
    public static final int MIN_SAMPLES = 4;
}
