package com.comedown.checkin.application;

import com.comedown.checkin.domain.MorningCheck;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 호흡 완료 유스케이스(아침 체크 ③). 결과·완료(DONE) 여부에는 영향이 없다.
 */
@Service
public class BreathingCommandService {

    private final MorningCheckProvisioner provisioner;
    private final ServiceTime serviceTime;

    BreathingCommandService(MorningCheckProvisioner provisioner, ServiceTime serviceTime) {
        this.provisioner = provisioner;
        this.serviceTime = serviceTime;
    }

    /** 오늘 체크에 호흡 완료 시각을 남긴다. 체크가 없으면 만든다. */
    @Transactional
    public void completeBreathing(long userId, LocalDate onboardingDate) {
        MorningCheck check = provisioner.ensure(userId, onboardingDate, serviceTime.today());
        check.completeBreathing(serviceTime.now());
    }
}
