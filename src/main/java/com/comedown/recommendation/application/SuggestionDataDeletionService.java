package com.comedown.recommendation.application;

import com.comedown.recommendation.contract.SuggestionDataDeletion;
import com.comedown.recommendation.domain.SuggestionStatus;
import com.comedown.recommendation.domain.SuggestionType;
import com.comedown.recommendation.infrastructure.persistence.SuggestionRepository;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제안의 건강 근거 삭제. 승인 진행 중(APPROVING)인 회복 추천은 외부 등록 결과 확인 전이므로 지우지 않고 근거만 지운다.
 */
@Service
public class SuggestionDataDeletionService implements SuggestionDataDeletion {

    static final List<SuggestionStatus> KEPT = List.of(SuggestionStatus.APPROVING, SuggestionStatus.APPROVED);

    private final SuggestionRepository suggestions;
    private final ServiceTime serviceTime;

    SuggestionDataDeletionService(SuggestionRepository suggestions, ServiceTime serviceTime) {
        this.suggestions = suggestions;
        this.serviceTime = serviceTime;
    }

    @Override
    @Transactional
    public Removed removeHealthBasis(long userId) {
        Instant now = serviceTime.now();
        int deleted = suggestions.deleteByTypeExceptStatuses(userId, SuggestionType.RECOVERY, KEPT);
        int stripped = suggestions.stripRecoveryBasis(userId, SuggestionType.RECOVERY, HEALTH_DATA_DELETED, now)
                + suggestions.clearJudgmentExceptType(userId, SuggestionType.RECOVERY, now);
        return new Removed(deleted, stripped);
    }
}
