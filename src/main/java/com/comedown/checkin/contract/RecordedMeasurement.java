package com.comedown.checkin.contract;

/**
 * 저장된 측정 결과 스냅샷.
 *
 * @param qualityLevel GOOD·FAIR·RETRY·UNVERIFIED
 * @param retryReason  RETRY일 때만 TOO_SHORT·LOW_FPS·NO_PULSE·TOO_NOISY
 * @param rmssdMs      RETRY면 null
 * @param meanHrBpm    RETRY·MANUAL이면 null
 */
public record RecordedMeasurement(
        long measurementId,
        long morningCheckId,
        String qualityLevel,
        String retryReason,
        Double rmssdMs,
        Double meanHrBpm) {
}
