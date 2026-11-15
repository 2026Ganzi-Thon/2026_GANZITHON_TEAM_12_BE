package com.comedown.recommendation.application;

import com.comedown.recommendation.contract.SuggestionCalendarDeletion;
import com.comedown.recommendation.domain.SuggestionStatus;
import com.comedown.recommendation.domain.SuggestionType;
import com.comedown.recommendation.infrastructure.persistence.SuggestionRepository;
import com.comedown.shared.time.ServiceTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캘린더 근거 삭제. 승인 진행 중(APPROVING)인 제안은 외부 등록 결과 확인 전이므로 지우지 않고 일정 참조만 끊는다.
 */
@Service
public class SuggestionCalendarDeletionService implements SuggestionCalendarDeletion {

    static final List<SuggestionStatus> KEPT = List.of(SuggestionStatus.APPROVING, SuggestionStatus.APPROVED);

    private final SuggestionRepository suggestions;
    private final ServiceTime time;

    SuggestionCalendarDeletionService(SuggestionRepository suggestions, ServiceTime time) {
        this.suggestions = suggestions;
        this.time = time;
    }

    @Override
    @Transactional
    public Removed removeCalendarBasis(long userId) {
        int deleted = suggestions.deleteByTypeExceptStatuses(userId, SuggestionType.PRE_START, KEPT);
        int detached = suggestions.detachRelatedEvents(userId, time.now());
        return new Removed(deleted, detached);
    }
}
