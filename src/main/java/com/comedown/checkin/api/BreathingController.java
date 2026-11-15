package com.comedown.checkin.api;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.checkin.application.BreathingCommandService;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ErrorResponse.FieldError;
import com.comedown.shared.error.FieldValidationException;
import com.comedown.shared.security.CurrentUser;
import com.comedown.shared.time.ServiceTime;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/morning-checks/today/breathing} → 204. 아침 체크 ③ 호흡 1분 완료(HTTP 책임: checkin.api).
 * 호흡은 건강정보가 아니므로 건강정보 동의·측정 켜짐 없이도 기록한다. 온보딩 완료는 필요하다(아침 측정 탭).
 * 건너뛰면 부르지 않으며 결과·완료 여부에 영향이 없다.
 */
@RestController
public class BreathingController {

    private final AccountAccessQuery accountAccess;
    private final BreathingCommandService breathing;
    private final ServiceTime serviceTime;
    private final Validator validator;

    public BreathingController(AccountAccessQuery accountAccess, BreathingCommandService breathing,
            ServiceTime serviceTime, Validator validator) {
        this.accountAccess = accountAccess;
        this.breathing = breathing;
        this.serviceTime = serviceTime;
        this.validator = validator;
    }

    /** @param durationSec 실제로 한 호흡 시간(초). 저장하지 않고 범위만 확인한다. */
    record BreathingRequest(
            @NotNull(message = "필수예요.") @Min(value = 1, message = "1~3600초여야 해요.")
            @Max(value = 3600, message = "1~3600초여야 해요.") Integer durationSec) {
    }

    @PostMapping("/api/morning-checks/today/breathing")
    public ResponseEntity<Void> complete(CurrentUser user, @RequestBody BreathingRequest request) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!access.active()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "탈퇴를 처리하고 있어요.");
        }
        if (!access.onboardingCompleted()) {
            throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
        }
        Set<ConstraintViolation<BreathingRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new FieldValidationException(violations.stream()
                    .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
                    .toList());
        }
        breathing.completeBreathing(user.id(), serviceTime.toServiceDate(access.onboardingCompletedAt()));
        return ResponseEntity.noContent().build();
    }
}
