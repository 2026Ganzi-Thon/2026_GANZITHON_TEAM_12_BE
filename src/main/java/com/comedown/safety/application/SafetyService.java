package com.comedown.safety.application;

import com.comedown.safety.contract.SafetyCommands;
import com.comedown.safety.domain.SafetyAlert;
import com.comedown.safety.domain.SafetyTrigger;
import com.comedown.safety.infrastructure.persistence.SafetyAlertRepository;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 안전 안내 평가·기록. */
@Service
public class SafetyService implements SafetyCommands {

    private final SafetyAlertRepository alerts;
    private final ServiceTime serviceTime;

    SafetyService(SafetyAlertRepository alerts, ServiceTime serviceTime) {
        this.alerts = alerts;
        this.serviceTime = serviceTime;
    }

    @Override
    @Transactional
    public boolean evaluateCbi(long userId, long assessmentId, boolean highRisk) {
        Instant now = serviceTime.now();
        alerts.findByUserIdAndTriggerTypeAndResolvedAtIsNull(userId, SafetyTrigger.CBI_HIGH)
                .forEach(open -> open.resolve(now));
        if (highRisk) {
            alerts.save(SafetyAlert.trigger(userId, SafetyTrigger.CBI_HIGH, String.valueOf(assessmentId), now));
        }
        return highRisk;
    }
}
