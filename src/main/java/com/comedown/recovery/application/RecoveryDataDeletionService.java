package com.comedown.recovery.application;

import com.comedown.recovery.contract.RecoveryDataDeletion;
import com.comedown.recovery.infrastructure.persistence.HrvDailyStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** recovery 건강 기록 삭제. */
@Service
public class RecoveryDataDeletionService implements RecoveryDataDeletion {

    private final HrvDailyStateRepository states;

    RecoveryDataDeletionService(HrvDailyStateRepository states) {
        this.states = states;
    }

    @Override
    @Transactional
    public int deleteAll(long userId) {
        return states.deleteAllOfUser(userId);
    }
}
