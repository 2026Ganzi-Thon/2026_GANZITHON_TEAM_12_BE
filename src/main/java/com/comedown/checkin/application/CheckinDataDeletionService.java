package com.comedown.checkin.application;

import com.comedown.checkin.contract.CheckinDataDeletion;
import com.comedown.checkin.infrastructure.persistence.HrvMeasurementRepository;
import com.comedown.checkin.infrastructure.persistence.MorningCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** checkin 건강 기록 삭제. 측정 → 호흡 없는 체크 → 남은 체크의 건강 필드 순으로 지운다. */
@Service
public class CheckinDataDeletionService implements CheckinDataDeletion {

    private final HrvMeasurementRepository measurements;
    private final MorningCheckRepository morningChecks;

    CheckinDataDeletionService(HrvMeasurementRepository measurements, MorningCheckRepository morningChecks) {
        this.measurements = measurements;
        this.morningChecks = morningChecks;
    }

    @Override
    @Transactional
    public Erased eraseHealthData(long userId) {
        int deletedMeasurements = measurements.deleteAllOfUser(userId);
        int deletedChecks = morningChecks.deleteWithoutBreathing(userId);
        int clearedChecks = morningChecks.clearHealthFields(userId);
        return new Erased(deletedMeasurements, clearedChecks, deletedChecks);
    }
}
