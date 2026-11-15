package com.comedown.experience.suggestions.application;

import com.comedown.account.contract.CalendarCredential;
import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.calendar.contract.CalendarEventWriter;
import com.comedown.calendar.contract.CalendarEventWriter.DeleteResult;
import com.comedown.calendar.contract.CalendarEventWriter.NewEvent;
import com.comedown.recommendation.contract.SuggestionApprovalCommands;
import com.comedown.recommendation.contract.SuggestionPlanning.TimeSlot;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ExternalServiceException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 제안 승인(구글 캘린더 등록)·되돌리기·다른 시간 조회. 이 클래스는 트랜잭션이 아니다: 상태 전이는 recommendation 계약의
 * 짧은 트랜잭션들이고, 구글 호출은 그 사이에 트랜잭션 밖에서 한다.
 * <pre>
 * 승인: beginApproval(APPROVING, 등록 키) → 구글에 등록 키를 event id로 등록 → confirmApproved
 *   - 확정된 거절(4xx)·자격 오류: markFailed (재시도는 FAILED → APPROVING)
 *   - 타임아웃·5xx(결과 모름): 실패로 확정하지 않고 등록 키로 조회해 확인. 못 알면 APPROVING으로 두고 202,
 *     재확인 작업({@link ApprovalRecheckJob})이 같은 키로 이어서 확인·재시도한다.
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
     * 이미 승인된 제안은 원래 결과를 그대로 돌려준다.
     */
    public Result approve(long userId, long suggestionId, Instant start, Instant end) {
        SuggestionView s = owned(userId, suggestionId);
        if ("APPROVED".equals(s.status())) {
            return new Result(s, true);
        }
        if (start != null || end != null) {
            validateChange(s, start, end, userId);
        }
        SuggestionView begun = approvals.beginApproval(userId, suggestionId, start, end);
        if ("APPROVED".equals(begun.status())) {
            return new Result(begun, true);
        }
        return register(begun, true);
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
                throw new ApiException(ErrorCode.INVALID_STATE, "Come-down이 만든 일정이 아니라 지울 수 없어요.");
            }
        } catch (ExternalServiceException e) {
            throw mapped(e);
        }
        return approvals.confirmUndone(userId, suggestionId);
    }

    /** 승인 중인 채 오래된 제안의 외부 결과를 다시 확인한다(재확인 작업). */
    void recheck(SuggestionView s) {
        register(s, false);
    }

    // ---------- 내부 ----------

    /** 등록 키로 구글에 등록하고(이미 있으면 만들지 않음) 결과에 따라 상태를 확정한다. */
    private Result register(SuggestionView s, boolean surfaceErrors) {
        CalendarCredential credential;
        try {
            credential = credentials.credentialFor(s.userId());
        } catch (ApiException e) {
            approvals.markFailed(s.userId(), s.id()); // 구글에 아무것도 보내지 않았으므로 확정 실패
            throw e;
        }
        NewEvent event = new NewEvent(s.registrationKey(), s.title(), s.start(), s.end());
        try {
            String googleEventId = writer.register(credential, event);
            return new Result(approvals.confirmApproved(s.userId(), s.id(), googleEventId), true);
        } catch (ExternalServiceException e) {
            if (e.kind() != ExternalServiceException.Kind.UNCERTAIN) {
                approvals.markFailed(s.userId(), s.id());
                if (surfaceErrors) {
                    throw mapped(e);
                }
                log.warn("제안 승인 확정 실패(suggestion={}, kind={})", s.id(), e.kind());
                return new Result(s, false);
            }
            return confirmIfRegistered(s, credential);
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
