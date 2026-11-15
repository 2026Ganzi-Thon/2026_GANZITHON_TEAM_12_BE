package com.comedown.account.application;

import com.comedown.account.contract.OnboardingCommands;
import com.comedown.account.infrastructure.persistence.AccountStore;
import com.comedown.account.infrastructure.persistence.OnboardingStepWriter;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 온보딩 단계 전진. 서버가 단계를 전진시키며(API 명세 0절), 완료된 단계를 되돌리지 않는다.
 * 프로필·생활 정보 단계는 {@link AccountService}가, CBI 제출·건너뛰기와 완료는 이 서비스가 전진시킨다.
 */
@Service
public class OnboardingProgressService implements OnboardingCommands {

    static final String CBI = "CBI";
    static final String LIFESTYLE = "LIFESTYLE";

    static final List<String> ORDER = List.of("PROFILE", "CBI", "LIFESTYLE", "SCHEDULE_REPORT", "DONE");

    private final OnboardingStepWriter writer;
    private final AccountStore accounts;
    private final ServiceTime time;

    public OnboardingProgressService(OnboardingStepWriter writer, AccountStore accounts, ServiceTime time) {
        this.writer = writer;
        this.accounts = accounts;
        this.time = time;
    }

    @Override
    @Transactional
    public String completeCbiStep(long userId) {
        writer.advance(userId, CBI, LIFESTYLE);
        return writer.currentStep(userId);
    }

    @Override
    @Transactional
    public String skipCbiStep(long userId) {
        String step = writer.currentStep(userId);
        if (ORDER.indexOf(step) < ORDER.indexOf(CBI)) {
            throw new ApiException(ErrorCode.ONBOARDING_STEP_INVALID);
        }
        var hrv = accounts.latestConsents(userId).get(AccountService.SENSITIVE_HRV);
        if (hrv != null && hrv.active()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "건강정보에 동의한 경우에는 CBI를 건너뛸 수 없어요.");
        }
        writer.advance(userId, CBI, LIFESTYLE);
        return writer.currentStep(userId);
    }

    @Override
    @Transactional
    public Instant completeOnboarding(long userId) {
        String step = writer.currentStep(userId);
        if (!"DONE".equals(step) && !"SCHEDULE_REPORT".equals(step)) {
            throw new ApiException(ErrorCode.ONBOARDING_STEP_INVALID);
        }
        writer.complete(userId, time.now());
        return accounts.findUser(userId).orElseThrow().completedAt();
    }
}
