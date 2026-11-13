package com.comedown.account.application;

import com.comedown.account.contract.DemoAccountSetup;
import com.comedown.account.infrastructure.persistence.DemoAccountWriter;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * local 전용 시연 계정 준비. 운영 프로필에서는 빈이 만들어지지 않는다.
 * 실제 온보딩 단계 전이(일정축)를 대신하지 않으며 {@code POST /api/dev/seed}만 사용한다.
 */
@Service
@Profile("local")
public class DemoAccountSetupService implements DemoAccountSetup {

    private final DemoAccountWriter writer;
    private final ServiceTime serviceTime;

    public DemoAccountSetupService(DemoAccountWriter writer, ServiceTime serviceTime) {
        this.writer = writer;
        this.serviceTime = serviceTime;
    }

    @Override
    @Transactional
    public void prepareOnboarded(long userId, Instant onboardingCompletedAt) {
        if (writer.completeOnboarding(userId, onboardingCompletedAt) == 0) {
            throw new IllegalStateException("사용자가 없습니다: " + userId);
        }
        Instant now = serviceTime.now();
        writer.enableHrv(userId, now);
        writer.ensureHrvConsent(userId, now);
    }
}
