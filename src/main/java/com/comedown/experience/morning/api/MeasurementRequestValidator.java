package com.comedown.experience.morning.api;

import com.comedown.experience.morning.application.ValidatedMeasurement;
import com.comedown.shared.error.ErrorResponse.FieldError;
import com.comedown.shared.error.FieldValidationException;
import com.comedown.shared.time.ServiceTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 측정 저장 요청 검증(400). 측정 시간 부족·프레임 부족은 여기서 막지 않고 품질 판정(RETRY)으로 처리한다.
 * <ul>
 *   <li>CAMERA: t·r·g 길이 동일, 각 최대 10,000개, 모든 값 유한수, r·g는 0~255</li>
 *   <li>MANUAL: RMSSD(ms)만, 0보다 큰 유한수</li>
 *   <li>공통: 측정 시작 시각은 미래일 수 없고 서버 수신 시각과 5분 넘게 차이 나면 안 된다(오늘 또는 어제 날짜)</li>
 * </ul>
 */
@Component
public class MeasurementRequestValidator {

    static final int MAX_FRAMES = 10_000;
    static final Duration MAX_CLOCK_GAP = Duration.ofMinutes(5);
    static final Set<String> TIME_SOURCES = Set.of("captureTime", "mediaTime", "performance");

    private final ServiceTime serviceTime;

    public MeasurementRequestValidator(ServiceTime serviceTime) {
        this.serviceTime = serviceTime;
    }

    public ValidatedMeasurement validate(MeasurementSaveRequest request) {
        List<FieldError> errors = new ArrayList<>();
        ValidatedMeasurement result = switch (request.source() == null ? "" : request.source()) {
            case "CAMERA" -> camera(request, errors);
            case "MANUAL" -> manual(request, errors);
            default -> {
                errors.add(new FieldError("source", "CAMERA 또는 MANUAL이어야 해요."));
                yield null;
            }
        };
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        return result;
    }

    private ValidatedMeasurement camera(MeasurementSaveRequest request, List<FieldError> errors) {
        Instant startedAt = time("startedAt", request.startedAt(), errors);
        if (request.timeSource() != null && !TIME_SOURCES.contains(request.timeSource())) {
            errors.add(new FieldError("timeSource", "captureTime, mediaTime, performance 중 하나여야 해요."));
        }
        double[] t = array("t", request.t(), false, errors);
        double[] r = array("r", request.r(), true, errors);
        double[] g = array("g", request.g(), true, errors);
        if (t != null && r != null && g != null && (t.length != r.length || t.length != g.length)) {
            errors.add(new FieldError("t", "t·r·g 배열의 길이가 같아야 해요."));
        }
        if (!errors.isEmpty()) {
            return null;
        }
        return new ValidatedMeasurement.Camera(startedAt, request.torch(), request.timeSource(), t, r, g);
    }

    private ValidatedMeasurement manual(MeasurementSaveRequest request, List<FieldError> errors) {
        Instant measuredAt = time("measuredAt", request.measuredAt(), errors);
        Double rmssd = request.rmssdMs();
        if (rmssd == null || !Double.isFinite(rmssd) || rmssd <= 0) {
            errors.add(new FieldError("rmssdMs", "0보다 큰 RMSSD(ms) 값이어야 해요."));
        }
        if (!errors.isEmpty()) {
            return null;
        }
        return new ValidatedMeasurement.Manual(measuredAt, rmssd);
    }

    private Instant time(String field, OffsetDateTime value, List<FieldError> errors) {
        if (value == null) {
            errors.add(new FieldError(field, "필수예요."));
            return null;
        }
        Instant instant = value.toInstant();
        Instant now = serviceTime.now();
        if (instant.isAfter(now)) {
            errors.add(new FieldError(field, "미래 시각은 보낼 수 없어요."));
        } else if (Duration.between(instant, now).compareTo(MAX_CLOCK_GAP) > 0) {
            errors.add(new FieldError(field, "기기 시각이 서버와 5분 넘게 달라요. 시간을 확인한 뒤 다시 측정해 주세요."));
        } else {
            LocalDate date = serviceTime.toServiceDate(instant);
            LocalDate today = serviceTime.today();
            if (!date.equals(today) && !date.equals(today.minusDays(1))) {
                errors.add(new FieldError(field, "오늘 또는 어제 측정만 저장할 수 있어요."));
            }
        }
        return instant;
    }

    private static double[] array(String field, List<Double> values, boolean brightness, List<FieldError> errors) {
        if (values == null || values.isEmpty()) {
            errors.add(new FieldError(field, "필수예요."));
            return null;
        }
        if (values.size() > MAX_FRAMES) {
            errors.add(new FieldError(field, "최대 10,000개까지 보낼 수 있어요."));
            return null;
        }
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            Double v = values.get(i);
            if (v == null || !Double.isFinite(v)) {
                errors.add(new FieldError(field, "모든 값이 유한한 숫자여야 해요."));
                return null;
            }
            if (brightness && (v < 0 || v > 255)) {
                errors.add(new FieldError(field, "밝기는 0~255여야 해요."));
                return null;
            }
            out[i] = v;
        }
        return out;
    }
}
