package com.comedown.account.application;

import com.comedown.account.contract.OnboardingCommands;
import com.comedown.account.infrastructure.persistence.OnboardingStepWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 온보딩 단계 전진. 서버가 단계를 전진시키며(API 명세 0절), 완료된 단계를 되돌리지 않는다.
 * 현재는 초기 CBI 제출(CBI → LIFESTYLE)만 제공한다. 나머지 단계 전이는 account 담당(일정축)이 이어서 추가한다.
 */
@Service
public class OnboardingProgressService implements OnboardingCommands {

    static final String CBI = "CBI";
    static final String LIFESTYLE = "LIFESTYLE";

    private final OnboardingStepWriter writer;

    public OnboardingProgressService(OnboardingStepWriter writer) {
        this.writer = writer;
    }

    @Override
    @Transactional
    public String completeCbiStep(long userId) {
        writer.advance(userId, CBI, LIFESTYLE);
        return writer.currentStep(userId);
    }
}
