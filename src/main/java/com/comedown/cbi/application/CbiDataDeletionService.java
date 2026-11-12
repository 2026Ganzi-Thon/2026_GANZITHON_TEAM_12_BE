package com.comedown.cbi.application;

import com.comedown.cbi.contract.CbiDataDeletion;
import com.comedown.cbi.infrastructure.persistence.CbiAssessmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** cbi 건강 기록 삭제. */
@Service
public class CbiDataDeletionService implements CbiDataDeletion {

    private final CbiAssessmentRepository assessments;

    CbiDataDeletionService(CbiAssessmentRepository assessments) {
        this.assessments = assessments;
    }

    @Override
    @Transactional
    public int deleteAll(long userId) {
        return assessments.deleteAllOfUser(userId);
    }
}
