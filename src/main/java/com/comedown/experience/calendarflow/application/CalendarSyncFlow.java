package com.comedown.experience.calendarflow.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.CalendarCredential;
import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.calendar.contract.CalendarEventSource;
import com.comedown.experience.suggestions.application.PreStartGenerationFlow;
import com.comedown.calendar.contract.SyncResult;
import com.comedown.calendar.contract.SyncedEvent;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.time.InstantRange;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code POST /api/calendar/sync}: 캘린더 이용 동의 확인 → 자격정보 → 구글 조회 → 일정 저장·분류 → 주간 부하 재계산.
 * 동의가 없으면 구글을 부르지도, 일정을 저장하지도 않는다(캘린더 권한만 받고 동의 없이 일정을 모으지 않는다).
 * 구글 호출은 어떤 DB 트랜잭션에도 속하지 않는다(각 단계가 자기 트랜잭션을 쓴다).
 */
@Service
public class CalendarSyncFlow {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncFlow.class);
    static final String CONSENT_REQUIRED_MESSAGE = "캘린더 이용 동의를 먼저 완료해 주세요.";

    public record Result(Instant from, Instant to, int fetched, SyncResult saved) {
    }

    private final AccountAccessQuery accountAccess;
    private final CalendarCredentialProvider credentials;
    private final CalendarEventSource source;
    private final CalendarSyncCommit commit;
    private final PreStartGenerationFlow preStart;
    private final ServiceTime time;

    public CalendarSyncFlow(AccountAccessQuery accountAccess, CalendarCredentialProvider credentials,
            CalendarEventSource source, CalendarSyncCommit commit, PreStartGenerationFlow preStart,
            ServiceTime time) {
        this.accountAccess = accountAccess;
        this.credentials = credentials;
        this.source = source;
        this.commit = commit;
        this.preStart = preStart;
        this.time = time;
    }

    public Result sync(long userId) {
        // 동의는 프로필 단계에서 기록한다. 그 전에 캘린더 권한만 받아 동기화하는 경로를 막는다.
        if (!accountAccess.calendarConsented(userId)) {
            throw new ApiException(ErrorCode.ONBOARDING_STEP_INVALID, CONSENT_REQUIRED_MESSAGE);
        }
        LocalDate thisMonday = time.currentWeekStart();
        LocalDate fromDate = earlier(time.today().minusMonths(1), thisMonday.minusWeeks(4));
        LocalDate toDate = thisMonday.plusWeeks(5);
        InstantRange range = new InstantRange(time.startOfDay(fromDate), time.startOfDay(toDate));

        CalendarCredential credential = credentials.credentialFor(userId);
        List<SyncedEvent> fetched;
        try {
            fetched = source.fetch(credential, range);
        } catch (ExternalServiceException e) {
            throw switch (e.kind()) {
                case UNAUTHORIZED -> new ApiException(ErrorCode.CALENDAR_REAUTH_REQUIRED);
                case INSUFFICIENT_SCOPE -> new ApiException(ErrorCode.CALENDAR_SCOPE_MISSING);
                case FAILURE, UNCERTAIN -> new ApiException(ErrorCode.CALENDAR_API_ERROR);
            };
        }
        SyncResult saved = commit.commit(userId, range, fetched, mondaysBetween(fromDate, toDate));
        refreshSuggestions(userId);
        return new Result(range.start(), range.endExclusive(), fetched.size(), saved);
    }

    /**
     * 동기화로 바뀐 위험 주에 맞춰 PRE_START 제안을 갱신한다. 일정 저장은 이미 커밋됐으므로 여기서 실패해도 동기화는
     * 성공으로 응답한다(제안은 다음 동기화나 {@code POST /api/suggestions/generate}에서 다시 만든다).
     */
    private void refreshSuggestions(long userId) {
        try {
            preStart.refresh(userId);
        } catch (RuntimeException e) {
            log.warn("동기화 후 PRE_START 제안 갱신 실패(userId={}): {}", userId, e.getClass().getSimpleName());
        }
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    /** {@code [fromDate, toDate)}에 걸친 모든 주의 월요일. */
    private List<LocalDate> mondaysBetween(LocalDate fromDate, LocalDate toDate) {
        List<LocalDate> mondays = new ArrayList<>();
        for (LocalDate monday = time.weekStartOf(fromDate); monday.isBefore(toDate); monday = monday.plusWeeks(1)) {
            mondays.add(monday);
        }
        return mondays;
    }
}
