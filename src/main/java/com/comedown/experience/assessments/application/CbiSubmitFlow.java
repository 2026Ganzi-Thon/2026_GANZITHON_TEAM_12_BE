package com.comedown.experience.assessments.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.account.contract.OnboardingCommands;
import com.comedown.cbi.contract.CbiCommands;
import com.comedown.cbi.contract.CbiResult;
import com.comedown.cbi.contract.SubmitCbi;
import com.comedown.checkin.contract.DayIndex;
import com.comedown.safety.contract.SafetyCommands;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.idempotency.IdempotencyService;
import com.comedown.shared.idempotency.IdempotentResponse;
import com.comedown.shared.time.ServiceTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * CBI 제출 흐름(가입 때·28일차 공용): 접근 조건 → 회차 조건 → 재시도 키로 보호한 한 트랜잭션에서
 * cbi 저장 → safety 평가(CBI_HIGH) → (INITIAL) 온보딩 CBI 단계 전진.
 * cbi는 safety·account를 호출하지 않고 이 흐름이 결과를 넘긴다.
 */
@Service
public class CbiSubmitFlow {

    /** 건강 기록 삭제 때 재시도 응답 캐시도 지운다. */
    public static final String OPERATION = "cbi-assessment:create";
    static final String PROFILE = "PROFILE";
    static final int DAY28 = 28;

    private final AccountAccessQuery accountAccess;
    private final OnboardingCommands onboarding;
    private final CbiCommands cbiCommands;
    private final SafetyCommands safetyCommands;
    private final IdempotencyService idempotency;
    private final ServiceTime serviceTime;

    public CbiSubmitFlow(AccountAccessQuery accountAccess, OnboardingCommands onboarding, CbiCommands cbiCommands,
            SafetyCommands safetyCommands, IdempotencyService idempotency, ServiceTime serviceTime) {
        this.accountAccess = accountAccess;
        this.onboarding = onboarding;
        this.cbiCommands = cbiCommands;
        this.safetyCommands = safetyCommands;
        this.idempotency = idempotency;
        this.serviceTime = serviceTime;
    }

    /** 접근 조건: 탈퇴 진행(409) → 건강정보 동의(403). CBI는 온보딩 중에도 허용한다. */
    public HealthDataAccess requireAccess(long userId) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!access.active()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "탈퇴를 처리하고 있어요.");
        }
        if (!access.sensitiveHrvConsented()) {
            throw new ApiException(ErrorCode.HRV_CONSENT_REQUIRED);
        }
        return access;
    }

    /**
     * 회차 조건(409): INITIAL은 프로필 단계를 마친 뒤, DAY28은 온보딩 완료 후 dayIndex ≥ 28.
     * 같은 회차 중복은 저장할 때 CBI_ALREADY_TAKEN.
     */
    public void requireRoundAllowed(HealthDataAccess access, String round) {
        if ("INITIAL".equals(round) && PROFILE.equals(access.onboardingStep())) {
            throw new ApiException(ErrorCode.ONBOARDING_STEP_INVALID);
        }
        if ("DAY28".equals(round)) {
            boolean due = access.onboardingCompleted() && DayIndex.of(
                    serviceTime.toServiceDate(access.onboardingCompletedAt()), serviceTime.today()) >= DAY28;
            if (!due) {
                throw new ApiException(ErrorCode.CBI_NOT_DUE);
            }
        }
    }

    public IdempotentResponse<CbiSubmitResult> submit(HealthDataAccess access, UUID idempotencyKey, Object request,
            String round, Map<Integer, Integer> options) {
        long userId = access.userId();
        return idempotency.execute(userId, OPERATION, idempotencyKey, request, CbiSubmitResult.class, () -> {
            CbiResult result = cbiCommands.submit(new SubmitCbi(userId, round, options));
            boolean show = safetyCommands.evaluateCbi(userId, result.id(), result.highRisk());
            String step = "INITIAL".equals(round) ? onboarding.completeCbiStep(userId) : access.onboardingStep();
            return IdempotentResponse.of(201, new CbiSubmitResult(
                    result.id(), result.round(), serviceTime.toResponse(result.takenAt()),
                    subscale(result.personalScore(), result.personalLevel()),
                    subscale(result.workScore(), result.workLevel()),
                    result.highRisk(),
                    new CbiSubmitResult.Safety(show),
                    step));
        });
    }

    private static CbiSubmitResult.Subscale subscale(BigDecimal score, String level) {
        return new CbiSubmitResult.Subscale(score.setScale(1, RoundingMode.HALF_UP), level);
    }
}
