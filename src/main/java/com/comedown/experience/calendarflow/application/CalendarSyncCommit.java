package com.comedown.experience.calendarflow.application;

import com.comedown.account.contract.AccountDeletion;
import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.calendar.contract.CalendarEventSync;
import com.comedown.calendar.contract.SyncResult;
import com.comedown.calendar.contract.SyncedEvent;
import com.comedown.schedule.contract.WeekLoads;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.InstantRange;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동기화 결과를 한 트랜잭션으로 저장한다(일정 저장 + 주간 부하 재계산). 사용자 행을 잠근 뒤 연결 상태를 다시 확인하므로,
 * 읽는 동안 캘린더 연결 해제·탈퇴가 끝났다면 아무것도 쓰지 않는다(삭제한 데이터가 다시 생기지 않는다).
 */
@Service
class CalendarSyncCommit {

    private final AccountDeletion lock;
    private final CalendarConnectionQuery connection;
    private final CalendarEventSync sync;
    private final WeekLoads weekLoads;

    CalendarSyncCommit(AccountDeletion lock, CalendarConnectionQuery connection, CalendarEventSync sync,
            WeekLoads weekLoads) {
        this.lock = lock;
        this.connection = connection;
        this.sync = sync;
        this.weekLoads = weekLoads;
    }

    @Transactional
    SyncResult commit(long userId, InstantRange range, List<SyncedEvent> fetched, List<LocalDate> weekStarts) {
        lock.lock(userId);
        if (!connection.isConnected(userId)) {
            throw new ApiException(ErrorCode.CALENDAR_NOT_CONNECTED);
        }
        SyncResult saved = sync.apply(userId, range, fetched);
        weekLoads.recalculate(userId, weekStarts);
        return saved;
    }
}
