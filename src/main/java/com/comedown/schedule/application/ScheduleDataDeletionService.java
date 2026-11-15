package com.comedown.schedule.application;

import com.comedown.schedule.contract.ScheduleDataDeletion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ScheduleDataDeletionService implements ScheduleDataDeletion {

    private final WeeklyLoadStore store;

    ScheduleDataDeletionService(WeeklyLoadStore store) {
        this.store = store;
    }

    @Override
    @Transactional
    public int deleteAll(long userId) {
        return store.deleteAll(userId);
    }
}
