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
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 제안 생성·상태 전이·조회. suggestions의 유일한 쓰기 소유자.
 * <p>
 * 상태 전이는 모두 사용자 잠금({@link SuggestionRepository#lockAllOfUser})을 잡고 읽는다. 버전 컬럼이 없어서, 잠그지 않고
 * 읽은 전이는 그 사이 커밋된 다른 전이(예: 승인 시작의 APPROVING)를 오래된 상태로 덮어쓴다.
 */
@Service
public class SuggestionService implements SuggestionCommands, SuggestionApprovalCommands, SuggestionQuery {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    /** Google event id 허용 문자(base32hex 소문자 0-9a-v)만 쓰는 등록 키 접두어. */
    static final String REGISTRATION_KEY_PREFIX = "cd";
    static final List<SuggestionStatus> RESERVED = List.of(SuggestionStatus.APPROVING, SuggestionStatus.APPROVED);

    private final SuggestionRepository suggestions;
    private final ServiceTime serviceTime;
    private final TransactionTemplate transactions;

    SuggestionService(SuggestionRepository suggestions, ServiceTime serviceTime,
            PlatformTransactionManager transactionManager) {
        this.suggestions = suggestions;
        this.serviceTime = serviceTime;
        this.transactions = new TransactionTemplate(transactionManager);
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
        Suggestion s = lockOwned(userId, suggestionId);
        s.dismiss(serviceTime.now());
        return toView(s);
    }

    @Override
    @Transactional
    public SuggestionView markDone(long userId, long suggestionId) {
        Suggestion s = lockOwned(userId, suggestionId);
        s.markDone(serviceTime.now());
        return toView(s);
    }

    /**
     * 사용자마다 짧은 트랜잭션으로 나눠 만료시킨다. 대상은 잠그지 않고 찾고, 바꾸기 전에 사용자 잠금으로 다시 읽어
     * 그 사이 승인이 시작된(APPROVING) 제안은 건드리지 않는다. 한 사용자의 실패가 나머지를 막지 않는다.
     */
    @Override
    public int expireDue() {
        Instant now = serviceTime.now();
        int expired = 0;
        for (Long userId : suggestions.findUserIdsWithDue(SuggestionStatus.PROPOSED, now)) {
            try {
                Integer changed = transactions.execute(tx -> expireDueOf(userId, now));
                expired += changed == null ? 0 : changed;
            } catch (RuntimeException e) {
                log.warn("제안 만료 처리 실패(user={}): {}", userId, e.getClass().getSimpleName());
            }
        }
        return expired;
    }

    private int expireDueOf(long userId, Instant now) {
        int expired = 0;
        for (Suggestion s : suggestions.lockAllOfUser(userId)) {
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
        return startApproval(userId, suggestionId, start, end).suggestion();
    }

    @Override
    @Transactional
    public ApprovalStart startApproval(long userId, long suggestionId, Instant start, Instant end) {
        Suggestion s = lockOwned(userId, suggestionId);
        if (s.getStatus() == SuggestionStatus.APPROVING || s.getStatus() == SuggestionStatus.APPROVED) {
            return new ApprovalStart(toView(s), false);
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
        return new ApprovalStart(toView(s), true);
    }

    @Override
    @Transactional
    public SuggestionView confirmApproved(long userId, long suggestionId, String googleEventId) {
        Suggestion s = lockOwned(userId, suggestionId);
        s.confirmApproved(googleEventId, serviceTime.now());
        return toView(s);
    }

    @Override
    @Transactional
    public SuggestionView markFailed(long userId, long suggestionId) {
        Suggestion s = lockOwned(userId, suggestionId);
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
        Suggestion s = lockOwned(userId, suggestionId);
        s.confirmUndone(serviceTime.now());
        return toView(s);
    }

    @Override
    @Transactional
    public boolean deferRecheck(long userId, long suggestionId) {
        Instant now = serviceTime.now();
        return findLocked(userId, suggestionId).map(s -> s.deferRecheck(now)).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SuggestionView> findStaleApproving(Instant cutoff, int limit) {
        return suggestions.findStale(SuggestionStatus.APPROVING, cutoff,
                org.springframework.data.domain.PageRequest.of(0, limit))
                .stream().map(SuggestionService::toView).toList();
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

    @Override
    @Transactional(readOnly = true)
    public Set<Long> referencedEventIds(long userId) {
        return Set.copyOf(suggestions.findRelatedEventIds(userId));
    }

    /** 잠그지 않고 읽는다(읽기 전용 확인). 상태를 바꿀 때는 {@link #lockOwned}. */
    private Suggestion owned(long userId, long suggestionId) {
        return suggestions.findByIdAndUserId(suggestionId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** 사용자 잠금을 잡고, 커밋된 최신 상태의 제안을 읽는다(상태 전이용). 남의 제안·없는 제안은 404. */
    private Suggestion lockOwned(long userId, long suggestionId) {
        return findLocked(userId, suggestionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Optional<Suggestion> findLocked(long userId, long suggestionId) {
        return suggestions.lockAllOfUser(userId).stream()
                .filter(s -> s.getId() == suggestionId)
                .findFirst();
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
