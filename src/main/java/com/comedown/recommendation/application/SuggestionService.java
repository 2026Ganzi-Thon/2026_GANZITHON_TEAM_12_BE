package com.comedown.recommendation.application;

import com.comedown.recommendation.contract.NewSuggestion;
import com.comedown.recommendation.contract.SuggestionApprovalCommands;
import com.comedown.recommendation.contract.SuggestionCommands;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.recommendation.domain.Suggestion;
import com.comedown.recommendation.domain.SuggestionStatus;
import com.comedown.recommendation.domain.SuggestionType;
import com.comedown.recommendation.infrastructure.persistence.SuggestionRepository;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 제안 생성·상태 전이·조회. suggestions의 유일한 쓰기 소유자. */
@Service
public class SuggestionService implements SuggestionCommands, SuggestionApprovalCommands, SuggestionQuery {

    /** Google event id 허용 문자(base32hex 소문자 0-9a-v)만 쓰는 등록 키 접두어. */
    static final String REGISTRATION_KEY_PREFIX = "cd";
    static final List<SuggestionStatus> RESERVED = List.of(SuggestionStatus.APPROVING, SuggestionStatus.APPROVED);

    private final SuggestionRepository suggestions;
    private final ServiceTime serviceTime;

    SuggestionService(SuggestionRepository suggestions, ServiceTime serviceTime) {
        this.suggestions = suggestions;
        this.serviceTime = serviceTime;
    }

    // ---------- 생성·사용자 행동 ----------

    @Override
    @Transactional
    public SuggestionView propose(NewSuggestion c) {
        Suggestion saved = suggestions.save(Suggestion.propose(
                c.userId(), SuggestionType.valueOf(c.type()), c.targetDate(), c.start(), c.end(), c.title(),
                c.activityCode(), c.judgmentState(), c.reasonCode(), c.calculationVersion(), c.message(),
                c.aiGenerated(), c.fallback(), c.relatedEventId(), c.riskWeekStart(), c.expiresAt(),
                newRegistrationKey(), serviceTime.now()));
        return toView(saved);
    }

    @Override
    @Transactional
    public SuggestionView dismiss(long userId, long suggestionId) {
        Suggestion s = owned(userId, suggestionId);
        s.dismiss(serviceTime.now());
        return toView(s);
    }

    @Override
    @Transactional
    public SuggestionView markDone(long userId, long suggestionId) {
        Suggestion s = owned(userId, suggestionId);
        s.markDone(serviceTime.now());
        return toView(s);
    }

    @Override
    @Transactional
    public int expireDue() {
        Instant now = serviceTime.now();
        int expired = 0;
        for (Suggestion s : suggestions.findDue(SuggestionStatus.PROPOSED, now)) {
            if (s.expireIfDue(now)) {
                expired++;
            }
        }
        return expired;
    }

    // ---------- 승인 흐름 ----------

    @Override
    @Transactional
    public SuggestionView beginApproval(long userId, long suggestionId, Instant start, Instant end) {
        suggestions.lockAllOfUser(userId);
        Suggestion s = owned(userId, suggestionId);
        if (s.getStatus() == SuggestionStatus.APPROVING || s.getStatus() == SuggestionStatus.APPROVED) {
            return toView(s);
        }
        if (s.getStatus() != SuggestionStatus.PROPOSED && s.getStatus() != SuggestionStatus.FAILED) {
            throw new ApiException(ErrorCode.SUGGESTION_NOT_PROPOSED);
        }
        Instant newStart = start == null ? s.getStartAt() : start;
        Instant newEnd = end == null ? s.getEndAt() : end;
        if (!suggestions.findOverlapping(userId, suggestionId, RESERVED, newStart, newEnd).isEmpty()) {
            throw new ApiException(ErrorCode.SLOT_CONFLICT);
        }
        try {
            s.beginApproval(start, end, serviceTime.now());
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
        return toView(s);
    }

    @Override
    @Transactional
    public SuggestionView confirmApproved(long userId, long suggestionId, String googleEventId) {
        Suggestion s = owned(userId, suggestionId);
        s.confirmApproved(googleEventId, serviceTime.now());
        return toView(s);
    }

    @Override
    @Transactional
    public SuggestionView markFailed(long userId, long suggestionId) {
        Suggestion s = owned(userId, suggestionId);
        s.markFailed(serviceTime.now());
        return toView(s);
    }

    @Override
    @Transactional(readOnly = true)
    public SuggestionView requireUndoable(long userId, long suggestionId) {
        Suggestion s = owned(userId, suggestionId);
        s.requireUndoable();
        return toView(s);
    }

    @Override
    @Transactional
    public SuggestionView confirmUndone(long userId, long suggestionId) {
        Suggestion s = owned(userId, suggestionId);
        s.confirmUndone(serviceTime.now());
        return toView(s);
    }

    // ---------- 조회 ----------

    @Override
    @Transactional(readOnly = true)
    public List<SuggestionView> search(long userId, String type, String status, LocalDate targetDate) {
        return suggestions.search(userId,
                        type == null ? null : SuggestionType.valueOf(type),
                        status == null ? null : SuggestionStatus.valueOf(status),
                        targetDate)
                .stream().map(SuggestionService::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SuggestionView> find(long userId, long suggestionId) {
        return suggestions.findByIdAndUserId(suggestionId, userId).map(SuggestionService::toView);
    }

    private Suggestion owned(long userId, long suggestionId) {
        return suggestions.findByIdAndUserId(suggestionId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    static String newRegistrationKey() {
        return REGISTRATION_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    static SuggestionView toView(Suggestion s) {
        return new SuggestionView(
                s.getId(), s.getUserId(), s.getType().name(), s.getStatus().name(), s.getTargetDate(),
                s.getStartAt(), s.getEndAt(), s.getTitle(), s.getActivityCode(), s.getReasonCode(),
                s.getJudgmentState(), s.getMessage(), s.isAiGenerated(), s.isFallback(), s.getRelatedEventId(),
                s.getRiskWeekStart(), s.getGoogleEventId(), s.getRegistrationKey(), s.getDoneAt());
    }
}
