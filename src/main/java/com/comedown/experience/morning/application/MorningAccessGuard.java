package com.comedown.experience.morning.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * 아침 체크 API의 접근 조건(API 명세 0절). 순서: 탈퇴 진행 → 온보딩 완료 → 건강정보 동의 → (측정만) 측정 켜짐.
 */
@Component
public class MorningAccessGuard {

    static final String DELETING_MESSAGE = "탈퇴를 처리하고 있어요.";

    private final AccountAccessQuery accountAccess;
    private final ServiceTime serviceTime;

    public MorningAccessGuard(AccountAccessQuery accountAccess, ServiceTime serviceTime) {
        this.accountAccess = accountAccess;
        this.serviceTime = serviceTime;
    }

    /**
     * 측정 저장: 온보딩 → 건강정보 동의 → 측정 켜짐.
     *
     * @return 온보딩 완료 서울 날짜(n일차 기준)
     */
    public LocalDate requireMeasurementAccess(long userId) {
        HealthDataAccess access = requireHealthDataAccess(userId);
        if (!access.hrvEnabled()) {
            throw new ApiException(ErrorCode.HRV_DISABLED);
        }
        return serviceTime.toServiceDate(access.onboardingCompletedAt());
    }

    /**
     * 컨디션 저장: 온보딩 → 건강정보 동의(측정 켜짐은 보지 않음).
     *
     * @return 온보딩 완료 서울 날짜(n일차 기준)
     */
    public LocalDate requireConditionAccess(long userId) {
        return serviceTime.toServiceDate(requireHealthDataAccess(userId).onboardingCompletedAt());
    }

    /**
     * 조회(오늘 체크 등): 온보딩 완료만 본다. 동의·측정 켜짐이 없어도 저장된 기록이 없을 뿐 조회는 막지 않는다.
     *
     * @return 온보딩 완료 서울 날짜(n일차 기준)
     */
    public LocalDate requireOnboarded(long userId) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!access.onboardingCompleted()) {
            throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
        }
        return serviceTime.toServiceDate(access.onboardingCompletedAt());
    }

    private HealthDataAccess requireHealthDataAccess(long userId) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!access.active()) {
            throw new ApiException(ErrorCode.INVALID_STATE, DELETING_MESSAGE);
        }
        if (!access.onboardingCompleted()) {
            throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
        }
        if (!access.sensitiveHrvConsented()) {
            throw new ApiException(ErrorCode.HRV_CONSENT_REQUIRED);
        }
        return access;
    }
}
