package com.comedown.checkin.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 수동(워치 값) 측정 저장 명령.
 *
 * @param onboardingDate 온보딩 완료 서울 날짜(n일차 계산 기준)
 * @param measuredAt     측정 시각. 측정 날짜 판정 기준
 * @param rmssdMs        RMSSD(ms). SDNN 등 다른 지표는 받지 않는다
 */
public record RecordManualMeasurement(long userId, LocalDate onboardingDate, Instant measuredAt, double rmssdMs) {

    public RecordManualMeasurement {
        Objects.requireNonNull(onboardingDate, "onboardingDate");
        Objects.requireNonNull(measuredAt, "measuredAt");
    }
}
