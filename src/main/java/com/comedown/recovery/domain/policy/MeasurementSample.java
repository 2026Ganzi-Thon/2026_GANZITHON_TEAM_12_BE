package com.comedown.recovery.domain.policy;

import java.time.Instant;
import java.util.Objects;

/**
 * 하루 대표값을 고르기 위한 측정 1건의 스냅샷. checkin의 측정 기록에서 필요한 값만 받는다.
 *
 * @param measuredAt   측정 시작 시각(UTC)
 * @param qualityLevel GOOD·FAIR·RETRY·UNVERIFIED
 * @param lnRmssd      ln(RMSSD). RETRY면 null
 */
public record MeasurementSample(Instant measuredAt, String qualityLevel, Double lnRmssd) {

    public MeasurementSample {
        Objects.requireNonNull(measuredAt, "measuredAt");
        Objects.requireNonNull(qualityLevel, "qualityLevel");
        if (lnRmssd != null && !Double.isFinite(lnRmssd)) {
            throw new IllegalArgumentException("lnRmssd는 유한수여야 합니다");
        }
    }
}
