package com.comedown.safety.application;

import com.comedown.safety.contract.SafetyDataDeletion;
import com.comedown.safety.infrastructure.persistence.SafetyAlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** safety 건강 기록 삭제. */
@Service
public class SafetyDataDeletionService implements SafetyDataDeletion {

    private final SafetyAlertRepository alerts;

    SafetyDataDeletionService(SafetyAlertRepository alerts) {
        this.alerts = alerts;
    }

    @Override
    @Transactional
    public int deleteAll(long userId) {
        return alerts.deleteAllOfUser(userId);
    }
}
