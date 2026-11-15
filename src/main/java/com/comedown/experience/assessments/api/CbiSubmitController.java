package com.comedown.experience.assessments.api;

import com.comedown.account.contract.HealthDataAccess;
import com.comedown.experience.assessments.application.CbiSubmitFlow;
import com.comedown.experience.assessments.application.CbiSubmitResult;
import com.comedown.shared.idempotency.IdempotentResponse;
import com.comedown.shared.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/cbi/assessments} → 201. CBI 제출(가입 때·28일차 공용, HTTP 책임: experience.assessments).
 * 순서: 접근 조건(403) → 형식 검증(400) → 회차 조건(409) → 재시도 키 → 저장·안전 평가·온보딩 전진.
 */
@RestController
public class CbiSubmitController {

    private final CbiSubmitFlow flow;
    private final CbiSubmitValidator validator;

    public CbiSubmitController(CbiSubmitFlow flow, CbiSubmitValidator validator) {
        this.flow = flow;
        this.validator = validator;
    }

    @PostMapping("/api/cbi/assessments")
    public ResponseEntity<CbiSubmitResult> submit(
            CurrentUser user,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CbiSubmitRequest request) {
        HealthDataAccess access = flow.requireAccess(user.id());
        Map<Integer, Integer> options = validator.validate(request);
        flow.requireRoundAllowed(access, request.round());
        IdempotentResponse<CbiSubmitResult> response =
                flow.submit(access, idempotencyKey, request, request.round(), options);
        return ResponseEntity.status(response.status()).body(response.body());
    }
}
