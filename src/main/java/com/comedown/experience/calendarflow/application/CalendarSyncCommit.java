package com.comedown.experience.calendarflow.application;

import com.comedown.account.contract.AccountDeletion;
import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.account.contract.CalendarSyncState;
import com.comedown.calendar.contract.CalendarEventSync;
import com.comedown.calendar.contract.SyncResult;
import com.comedown.calendar.contract.SyncedEvent;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.schedule.contract.WeekLoads;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.InstantRange;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동기화 결과를 한 트랜잭션으로 저장한다(일정 저장 + 주간 부하 재계산 + 마지막 동기화 시각 기록). 사용자 행을 잠근 뒤 연결 상태를 다시 확인하므로,
 * 읽는 동안 캘린더 연결 해제·탈퇴가 끝났다면 아무것도 쓰지 않는다(삭제한 데이터가 다시 생기지 않는다).
 */
@Service
class CalendarSyncCommit {

    private final AccountDeletion lock;
    private final CalendarConnectionQuery connection;
    private final CalendarEventSync sync;
    private final WeekLoads weekLoads;
    private final SuggestionQuery suggestions;
    private final CalendarSyncState syncState;
    private final ServiceTime time;

    CalendarSyncCommit(AccountDeletion lock, CalendarConnectionQuery connection, CalendarEventSync sync,
            WeekLoads weekLoads, SuggestionQuery suggestions, CalendarSyncState syncState, ServiceTime time) {
        this.lock = lock;
        this.connection = connection;
        this.sync = sync;
        this.weekLoads = weekLoads;
        this.suggestions = suggestions;
        this.syncState = syncState;
        this.time = time;
    }

    @Transactional
    SyncResult commit(long userId, InstantRange range, List<SyncedEvent> fetched, List<LocalDate> weekStarts) {
        lock.lock(userId);
        if (!connection.isConnected(userId)) {
            throw new ApiException(ErrorCode.CALENDAR_NOT_CONNECTED);
        }
        // 제안 생성도 같은 사용자 잠금을 쓰므로, 잠금 안에서 읽은 참조 목록은 저장이 끝날 때까지 바뀌지 않는다.
        SyncResult saved = sync.apply(userId, range, fetched, suggestions.referencedEventIds(userId));
        weekLoads.recalculate(userId, weekStarts);
        syncState.markSynced(userId, time.now()); // 일정이 0건이어도 동기화한 시각을 남긴다
        return saved;
    }
}
