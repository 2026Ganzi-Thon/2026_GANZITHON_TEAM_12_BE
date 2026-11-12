package com.comedown.checkin.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 카메라 측정 저장 명령.
 *
 * @param onboardingDate 온보딩 완료 서울 날짜(n일차 계산 기준)
 * @param startedAt      측정 시작(손가락 감지) 시각. 측정 날짜 판정 기준
 * @param t              프레임 시각(ms, 시작 = 0)
 * @param r              프레임별 빨강 평균 밝기
 * @param g              프레임별 초록 평균 밝기
 */
public record RecordCameraMeasurement(
        long userId,
        LocalDate onboardingDate,
        Instant startedAt,
        Boolean torch,
        String timeSource,
        double[] t,
        double[] r,
        double[] g) {

    public RecordCameraMeasurement {
        Objects.requireNonNull(onboardingDate, "onboardingDate");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(t, "t");
        Objects.requireNonNull(r, "r");
        Objects.requireNonNull(g, "g");
    }
}
