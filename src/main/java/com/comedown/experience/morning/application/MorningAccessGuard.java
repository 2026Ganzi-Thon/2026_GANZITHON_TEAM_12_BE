package com.comedown.experience.morning.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.AccountDeletion;
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

    /**
     * 조회 접근 결과.
     *
     * @param onboardingDate    온보딩 완료 서울 날짜(n일차 기준)
     * @param healthDataVisible 건강정보 동의가 유효해 저장된 건강 기록을 보여줄 수 있는지
     */
    public record ReadAccess(LocalDate onboardingDate, boolean healthDataVisible) {
    }

    private final AccountAccessQuery accountAccess;
    private final AccountDeletion userLock;
    private final ServiceTime serviceTime;

    public MorningAccessGuard(AccountAccessQuery accountAccess, AccountDeletion userLock, ServiceTime serviceTime) {
        this.accountAccess = accountAccess;
        this.userLock = userLock;
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
     * 저장 트랜잭션 안에서 호출한다(측정). 사용자 행을 잠가 건강 기록 삭제·동의 철회·탈퇴와 직렬화한 뒤 접근 조건을
     * 다시 확인한다. Controller의 확인과 저장 사이에 철회·탈퇴가 끝났다면 아무것도 쓰지 않는다(지운 건강 기록이 다시
     * 생기지 않는다).
     */
    public void recheckMeasurementAccessInTransaction(long userId) {
        userLock.lock(userId);
        requireMeasurementAccess(userId);
    }

    /** 저장 트랜잭션 안에서 호출한다(컨디션). {@link #recheckMeasurementAccessInTransaction}과 같은 이유다. */
    public void recheckConditionAccessInTransaction(long userId) {
        userLock.lock(userId);
        requireHealthDataAccess(userId);
    }

    /**
     * 조회(오늘 체크 등): 온보딩 완료만 본다. 동의·측정 켜짐이 없어도 조회는 막지 않는다. 다만 건강정보 동의가 유효하지
     * 않으면(철회하고 기록을 남긴 경우 포함) 저장된 건강 기록은 보여주지 않는다.
     */
    public ReadAccess requireOnboarded(long userId) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!access.onboardingCompleted()) {
            throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
        }
        return new ReadAccess(serviceTime.toServiceDate(access.onboardingCompletedAt()),
                access.sensitiveHrvConsented());
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
