package com.comedown.experience.morning.application;

/**
 * {@code POST /api/hrv/measurements} 응답(API 명세 6절). 재시도 키 재생을 위해 그대로 저장된다.
 */
public record MeasurementSaveResult(Measurement measurement, long morningCheckId) {

    /**
     * @param retryRequired RETRY면 true → retryReason별 재측정 안내
     */
    public record Measurement(
            long id,
            String qualityLevel,
            boolean retryRequired,
            String retryReason,
            Double rmssdMs,
            Double meanHrBpm) {
    }
}
