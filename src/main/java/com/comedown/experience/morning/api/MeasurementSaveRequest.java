package com.comedown.experience.morning.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code POST /api/hrv/measurements} 요청(API 명세 6절). source에 따라 쓰는 필드가 다르다.
 * <ul>
 *   <li>CAMERA: startedAt, torch, timeSource, t, r, g</li>
 *   <li>MANUAL: measuredAt, rmssdMs</li>
 * </ul>
 * 배열은 최대 10,000개라 요소별 Bean Validation 대신 {@link MeasurementRequestValidator}에서 검사한다.
 */
public record MeasurementSaveRequest(
        String source,
        OffsetDateTime startedAt,
        Boolean torch,
        String timeSource,
        List<Double> t,
        List<Double> r,
        List<Double> g,
        OffsetDateTime measuredAt,
        Double rmssdMs) {
}
