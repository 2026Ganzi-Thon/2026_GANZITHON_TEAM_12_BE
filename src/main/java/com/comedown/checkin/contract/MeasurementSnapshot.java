package com.comedown.checkin.contract;

import java.time.Instant;

/**
 * 측정 1건의 읽기 스냅샷.
 *
 * @param qualityLevel GOOD·FAIR·RETRY·UNVERIFIED
 * @param lnRmssd      RETRY면 null
 * @param meanHrBpm    RETRY·MANUAL이면 null
 */
public record MeasurementSnapshot(
        long id,
        Instant measuredAt,
        String qualityLevel,
        Double rmssdMs,
        Double lnRmssd,
        Double meanHrBpm) {
}
