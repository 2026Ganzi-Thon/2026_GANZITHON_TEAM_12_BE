package com.comedown.experience.morning.api;

import com.comedown.experience.morning.application.MeasurementSaveFlow;
import com.comedown.experience.morning.application.MeasurementSaveResult;
import com.comedown.experience.morning.application.ValidatedMeasurement;
import com.comedown.shared.idempotency.IdempotentResponse;
import com.comedown.shared.security.CurrentUser;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/hrv/measurements} → 201. 아침 체크 ① 손가락 측정 저장(HTTP 책임: experience.morning).
 * 순서: 접근 조건(403) → 요청 검증(400) → 재시도 키 처리 → checkin 저장.
 */
@RestController
public class HrvMeasurementController {

    private final MeasurementSaveFlow flow;
    private final MeasurementRequestValidator validator;

    public HrvMeasurementController(MeasurementSaveFlow flow, MeasurementRequestValidator validator) {
        this.flow = flow;
        this.validator = validator;
    }

    @PostMapping("/api/hrv/measurements")
    public ResponseEntity<MeasurementSaveResult> save(
            CurrentUser user,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody MeasurementSaveRequest request) {
        LocalDate onboardingDate = flow.requireMeasurementAccess(user.id());
        ValidatedMeasurement measurement = validator.validate(request);
        IdempotentResponse<MeasurementSaveResult> response =
                flow.save(user.id(), onboardingDate, idempotencyKey, request, measurement);
        return ResponseEntity.status(response.status()).body(response.body());
    }
}
