package com.comedown.experience.suggestions.application;

import com.comedown.account.contract.CalendarCredential;
import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.calendar.contract.CalendarEventWriter;
import com.comedown.calendar.contract.CalendarEventWriter.DeleteResult;
import com.comedown.calendar.contract.CalendarEventWriter.NewEvent;
import com.comedown.recommendation.contract.SuggestionApprovalCommands;
import com.comedown.recommendation.contract.SuggestionApprovalCommands.ApprovalStart;
import com.comedown.recommendation.contract.SuggestionPlanning.TimeSlot;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ExternalServiceException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 제안 승인(구글 캘린더 등록)·되돌리기·다른 시간 조회. 이 클래스는 트랜잭션이 아니다: 상태 전이는 recommendation 계약의
 * 짧은 트랜잭션들이고, 구글 호출은 그 사이에 트랜잭션 밖에서 한다.
 * <pre>
 * 승인: (시간을 바꾸지 않으면) 제안한 시간이 아직 비어 있는지 재확인 → beginApproval(APPROVING, 등록 키)
 *       → 구글에 등록 키를 event id로 등록 → confirmApproved
 *   - 제안한 시간이 지났거나 그 사이 같은 시간에 일정이 생겼으면 409 SLOT_CONFLICT. 제안은 PROPOSED(재시도면 FAILED)
 *     그대로이고 구글에 아무것도 보내지 않는다.
 *   - 확정된 거절(4xx)·자격 오류: markFailed (재시도는 FAILED → APPROVING)
 *   - 타임아웃·5xx(결과 모름): 실패로 확정하지 않고 등록 키로 조회해 확인. 못 알면 APPROVING으로 두고 202,
 *     재확인 작업({@link ApprovalRecheckJob})이 같은 키로 이어서 확인·재시도한다.
 *   - 재확인·이미 APPROVING인 제안의 재승인: 같은 키로 이전 시도가 있었을 수 있으므로, 거절·자격 오류여도 구글에 그 일정이
 *     없다고 확인된 뒤에만 markFailed. 확인하지 못하면 APPROVING으로 둔다.
 * </pre>
 */
@Service
public class SuggestionApprovalFlow {

    private static final Logger log = LoggerFactory.getLogger(SuggestionApprovalFlow.class);

    /** 승인 결과. {@code confirmed}가 false면 외부 결과를 확인하는 중이다(202). */
    public record Result(SuggestionView suggestion, boolean confirmed) {
    }

    private final SuggestionQuery query;
    private final SuggestionApprovalCommands approvals;
    private final CalendarCredentialProvider credentials;
    private final CalendarEventWriter writer;
    private final SlotService slots;

    public SuggestionApprovalFlow(SuggestionQuery query, SuggestionApprovalCommands approvals,
            CalendarCredentialProvider credentials, CalendarEventWriter writer, SlotService slots) {
        this.query = query;
        this.approvals = approvals;
        this.credentials = credentials;
        this.writer = writer;
        this.slots = slots;
    }

    /** 다른 시간 보기: 같은 날·같은 길이로 넣을 수 있는 빈 시간(최대 5개). PROPOSED·FAILED 제안만. */
    public List<TimeSlot> slots(long userId, long suggestionId) {
        SuggestionView s = owned(userId, suggestionId);
        if (!"PROPOSED".equals(s.status()) && !"FAILED".equals(s.status())) {
            throw new ApiException(ErrorCode.SUGGESTION_NOT_PROPOSED);
        }
        return slots.alternatives(s, query.search(userId, null, null, s.targetDate()));
    }

    /**
     * 승인. {@code start}/{@code end}가 있으면 시간을 바꾼다(같은 날·같은 길이·빈 시간이 아니면 400).
     * 없으면 제안한 시간 그대로 등록하되, 그 시간이 지났거나 그 사이 일정이 생겼으면 409 SLOT_CONFLICT.
     * 이미 승인된 제안은 원래 결과를 그대로 돌려주고, 이미 승인 중(APPROVING)인 제안은 재확인 없이 같은 등록 키로 이어 간다
     * (이전 시도가 구글에 반영됐을 수 있어서, 여기서 막으면 그 결과를 확정하지 못한다).
     */
    public Result approve(long userId, long suggestionId, Instant start, Instant end) {
        SuggestionView s = owned(userId, suggestionId);
        if ("APPROVED".equals(s.status())) {
            return new Result(s, true);
        }
        if (start != null || end != null) {
            validateChange(s, start, end, userId);
        } else if (startsNewApproval(s)) {
            requireStillFree(s);
        }
        ApprovalStart begun = approvals.startApproval(userId, suggestionId, start, end);
        if ("APPROVED".equals(begun.suggestion().status())) {
            return new Result(begun.suggestion(), true);
        }
        // 이번 호출이 승인을 시작한 것이 아니면(이미 APPROVING) 이전 시도가 구글에 반영됐을 수 있다.
        return register(begun.suggestion(), true, !begun.started());
    }

    /** 되돌리기: Come-down이 만든 구글 일정만 지우고 UNDONE. 이미 UNDONE이면 같은 결과. */
    public SuggestionView undo(long userId, long suggestionId) {
        SuggestionView s = approvals.requireUndoable(userId, suggestionId);
        if ("UNDONE".equals(s.status())) {
            return s;
        }
        CalendarCredential credential = credentials.credentialFor(userId);
        try {
            DeleteResult result = writer.deleteAppEvent(credential, s.googleEventId());
            if (result == DeleteResult.NOT_APP_OWNED) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Come & Down이 만든 일정이 아니라 지울 수 없어요.");
            }
        } catch (ExternalServiceException e) {
            throw mapped(e);
        }
        return approvals.confirmUndone(userId, suggestionId);
    }

    /** 승인 중인 채 오래된 제안의 외부 결과를 다시 확인한다(재확인 작업). */
    void recheck(SuggestionView s) {
        register(s, false, true);
    }

    // ---------- 내부 ----------

    /**
     * 등록 키로 구글에 등록하고(이미 있으면 만들지 않음) 결과에 따라 상태를 확정한다.
     *
     * @param earlierAttemptPossible 같은 등록 키로 이전 시도가 있었을 수 있는지(재확인 작업, 이미 APPROVING인 제안의 재승인).
     *                               이전 시도는 결과를 모르는 채 끝났을 수 있으므로, 이번 시도가 거절돼도 구글에 일정이
     *                               없다고 확인되기 전에는 FAILED로 확정하지 않는다.
     */
    private Result register(SuggestionView s, boolean surfaceErrors, boolean earlierAttemptPossible) {
        CalendarCredential credential;
        try {
            credential = credentials.credentialFor(s.userId());
        } catch (ApiException e) {
            if (!earlierAttemptPossible) {
                approvals.markFailed(s.userId(), s.id()); // 구글에 아무것도 보낸 적이 없으므로 확정 실패
                throw e;
            }
            // 이전 시도의 결과를 확인할 자격이 없다. 실패로 확정하지 않고 APPROVING으로 둔다(자격이 돌아오면 재확인이 끝낸다).
            log.warn("제안 승인 결과를 확인할 캘린더 자격이 없습니다(suggestion={}, code={})", s.id(), e.errorCode());
            if (surfaceErrors) {
                throw e;
            }
            return new Result(s, false);
        }
        NewEvent event = new NewEvent(s.registrationKey(), s.title(), s.start(), s.end());
        try {
            String googleEventId = writer.register(credential, event);
            return new Result(approvals.confirmApproved(s.userId(), s.id(), googleEventId), true);
        } catch (ExternalServiceException e) {
            if (e.kind() == ExternalServiceException.Kind.UNCERTAIN) {
                return confirmIfRegistered(s, credential);
            }
            if (earlierAttemptPossible) {
                Optional<Result> settled = settleEarlierAttempt(s, credential);
                if (settled.isPresent()) {
                    return settled.get();
                }
            }
            approvals.markFailed(s.userId(), s.id());
            if (surfaceErrors) {
                throw mapped(e);
            }
            log.warn("제안 승인 확정 실패(suggestion={}, kind={})", s.id(), e.kind());
            return new Result(s, false);
        }
    }

    /**
     * 이번 등록이 거절됐을 때, 이전 시도가 만든 일정이 있는지 등록 키로 확인한다.
     *
     * @return 일정이 있으면 APPROVED로 확정한 결과, 확인하지 못했으면 APPROVING 그대로인 결과. 일정이 없다고 확인되면
     *         비어 있다(호출하는 쪽이 확정 실패로 처리).
     */
    private Optional<Result> settleEarlierAttempt(SuggestionView s, CalendarCredential credential) {
        try {
            if (writer.exists(credential, s.registrationKey())) {
                return Optional.of(
                        new Result(approvals.confirmApproved(s.userId(), s.id(), s.registrationKey()), true));
            }
            return Optional.empty();
        } catch (ExternalServiceException e) {
            log.warn("이전 승인 시도의 결과를 확인하지 못했습니다(suggestion={}, kind={})", s.id(), e.kind());
            return Optional.of(new Result(s, false));
        }
    }

    /** 결과를 모를 때: 등록 키로 조회해 있으면 확정하고, 못 알면 APPROVING으로 둔다. */
    private Result confirmIfRegistered(SuggestionView s, CalendarCredential credential) {
        try {
            if (writer.exists(credential, s.registrationKey())) {
                return new Result(approvals.confirmApproved(s.userId(), s.id(), s.registrationKey()), true);
            }
        } catch (ExternalServiceException e) {
            log.warn("제안 승인 결과를 확인하지 못했습니다(suggestion={}, kind={})", s.id(), e.kind());
        }
        return new Result(s, false);
    }

    private void validateChange(SuggestionView s, Instant start, Instant end, long userId) {
        if (start == null || end == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "시작과 종료 시각을 함께 보내 주세요.");
        }
        if (!end.isAfter(start) || !slots.fits(s, new TimeSlot(start, end), query.search(userId, null, null,
                s.targetDate()))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "같은 날 같은 길이로, 수면 시간과 다른 일정을 피해 정해 주세요.");
        }
    }

    /** 제안한 시간 그대로 승인하기 전 재확인. 지났거나 그 사이 일정이 생겼으면 409 SLOT_CONFLICT(상태는 바꾸지 않는다). */
    private void requireStillFree(SuggestionView s) {
        if (slots.alreadyStarted(s)) {
            throw new ApiException(ErrorCode.SLOT_CONFLICT, "제안한 시간이 이미 지났어요. 다른 시간을 골라 주세요.");
        }
        if (slots.takenByEvent(s)) {
            throw new ApiException(ErrorCode.SLOT_CONFLICT);
        }
    }

    /** 이번 승인이 구글에 처음 보내는 시도가 되는 상태(PROPOSED, 확정 실패 뒤 재시도인 FAILED). */
    private static boolean startsNewApproval(SuggestionView s) {
        return "PROPOSED".equals(s.status()) || "FAILED".equals(s.status());
    }

    private SuggestionView owned(long userId, long suggestionId) {
        return query.find(userId, suggestionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    static ApiException mapped(ExternalServiceException e) {
        return new ApiException(switch (e.kind()) {
            case UNAUTHORIZED -> ErrorCode.CALENDAR_REAUTH_REQUIRED;
            case INSUFFICIENT_SCOPE -> ErrorCode.CALENDAR_SCOPE_MISSING;
            case FAILURE, UNCERTAIN -> ErrorCode.CALENDAR_API_ERROR;
        });
    }
}
