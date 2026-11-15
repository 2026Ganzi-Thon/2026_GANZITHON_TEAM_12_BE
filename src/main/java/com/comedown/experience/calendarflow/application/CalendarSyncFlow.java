package com.comedown.experience.calendarflow.application;

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
import org.springframework.stereotype.Service;

/**
 * {@code POST /api/calendar/sync}: 자격정보 → 구글 조회 → 일정 저장·분류 → 주간 부하 재계산.
 * 구글 호출은 어떤 DB 트랜잭션에도 속하지 않는다(각 단계가 자기 트랜잭션을 쓴다).
 */
@Service
public class CalendarSyncFlow {

    public record Result(Instant from, Instant to, int fetched, SyncResult saved) {
    }

    private final CalendarCredentialProvider credentials;
    private final CalendarEventSource source;
    private final CalendarSyncCommit commit;
    private final PreStartGenerationFlow preStart;
    private final ServiceTime time;

    public CalendarSyncFlow(CalendarCredentialProvider credentials, CalendarEventSource source,
            CalendarSyncCommit commit, PreStartGenerationFlow preStart, ServiceTime time) {
        this.credentials = credentials;
        this.source = source;
        this.commit = commit;
        this.preStart = preStart;
        this.time = time;
    }

    public Result sync(long userId) {
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
        preStart.refresh(userId); // 동기화로 바뀐 위험 주에 맞춰 PRE_START 제안을 갱신한다
        return new Result(range.start(), range.endExclusive(), fetched.size(), saved);
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
