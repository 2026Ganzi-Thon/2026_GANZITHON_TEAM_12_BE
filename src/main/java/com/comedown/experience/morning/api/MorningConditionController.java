package com.comedown.experience.morning.api;

import com.comedown.experience.morning.application.ConditionSaveFlow;
import com.comedown.experience.morning.application.MorningAccessGuard;
import com.comedown.experience.morning.application.TodayResult;
import com.comedown.shared.error.ErrorResponse.FieldError;
import com.comedown.shared.error.FieldValidationException;
import com.comedown.shared.security.CurrentUser;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /api/morning-checks/today/condition} → 200 TodayResult. 아침 체크 ② 컨디션 체크(HTTP 책임: experience.morning).
 * 순서: 접근 조건(403: 온보딩 → 건강정보 동의) → 요청 검증(400) → 저장·재계산·결과.
 * 접근 조건을 먼저 보기 위해 Bean Validation은 {@code @Valid} 대신 직접 실행한다.
 */
@RestController
public class MorningConditionController {

    private final MorningAccessGuard accessGuard;
    private final ConditionSaveFlow flow;
    private final Validator validator;

    public MorningConditionController(MorningAccessGuard accessGuard, ConditionSaveFlow flow, Validator validator) {
        this.accessGuard = accessGuard;
        this.flow = flow;
        this.validator = validator;
    }

    @PutMapping("/api/morning-checks/today/condition")
    public TodayResult saveCondition(CurrentUser user, @RequestBody ConditionRequest request) {
        LocalDate onboardingDate = accessGuard.requireConditionAccess(user.id());
        validate(request);
        return flow.save(user.id(), onboardingDate, request.score(), request.alcohol(), request.sick(),
                request.caffeine());
    }

    private void validate(ConditionRequest request) {
        Set<ConstraintViolation<ConditionRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            List<FieldError> errors = violations.stream()
                    .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
                    .sorted(Comparator.comparing(FieldError::field))
                    .toList();
            throw new FieldValidationException(errors);
        }
    }
}
