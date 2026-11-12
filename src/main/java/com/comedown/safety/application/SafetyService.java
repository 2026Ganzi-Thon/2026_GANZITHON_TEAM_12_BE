package com.comedown.safety.application;

import com.comedown.safety.contract.RecoveryDayStatus;
import com.comedown.safety.contract.SafetyAlertView;
import com.comedown.safety.contract.SafetyCommands;
import com.comedown.safety.contract.SafetyQuery;
import com.comedown.safety.domain.SafetyAlert;
import com.comedown.safety.domain.SafetyTrigger;
import com.comedown.safety.domain.policy.LowRecoveryRule;
import com.comedown.safety.infrastructure.persistence.SafetyAlertRepository;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 안전 안내 평가·기록·조회. */
@Service
public class SafetyService implements SafetyCommands, SafetyQuery {

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

    @Override
    @Transactional
    public void evaluateLowRecovery(long userId, LocalDate today, List<RecoveryDayStatus> days) {
        Instant now = serviceTime.now();
        Optional<LocalDate> lowSince = LowRecoveryRule.evaluate(days, today);
        List<SafetyAlert> open =
                alerts.findByUserIdAndTriggerTypeAndResolvedAtIsNull(userId, SafetyTrigger.LOW_RECOVERY_2W);
        if (lowSince.isEmpty()) {
            open.forEach(alert -> alert.resolve(now));
            return;
        }
        if (open.isEmpty()) {
            alerts.save(SafetyAlert.trigger(userId, SafetyTrigger.LOW_RECOVERY_2W, lowSince.get().toString(), now));
        }
    }

    @Override
    @Transactional
    public void acknowledge(long userId, long alertId) {
        alerts.findByIdAndUserId(alertId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND))
                .acknowledge(serviceTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SafetyAlertView> findOpenAlerts(long userId) {
        return alerts.findByUserIdOrderByTriggeredAtDescIdDesc(userId).stream()
                .filter(SafetyAlert::isOpen)
                .map(a -> new SafetyAlertView(a.getId(), a.getTriggerType().name(), a.getTriggeredAt(),
                        a.getAcknowledgedAt() != null))
                .toList();
    }
}
