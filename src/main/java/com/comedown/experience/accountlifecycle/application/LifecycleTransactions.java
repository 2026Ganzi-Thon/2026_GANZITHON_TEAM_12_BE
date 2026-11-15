package com.comedown.experience.accountlifecycle.application;

import com.comedown.account.contract.AccountCommands;
import com.comedown.account.contract.AccountDeletion;
import com.comedown.account.contract.GoogleAccessRevocation;
import com.comedown.account.contract.UserSessions;
import com.comedown.calendar.contract.CalendarDataDeletion;
import com.comedown.cbi.contract.CbiDataDeletion;
import com.comedown.checkin.contract.CheckinDataDeletion;
import com.comedown.recommendation.contract.SuggestionCalendarDeletion;
import com.comedown.recommendation.contract.SuggestionDataDeletion;
import com.comedown.recovery.contract.RecoveryDataDeletion;
import com.comedown.safety.contract.SafetyDataDeletion;
import com.comedown.schedule.contract.ScheduleDataDeletion;
import com.comedown.shared.idempotency.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 삭제 유스케이스의 DB 단계들. 각 메서드는 한 트랜잭션이며 구글 호출은 포함하지 않는다(구글 호출은
 * {@link AccountLifecycleFlow}가 트랜잭션 밖에서 한다). 각 기능은 자기 테이블만 지우고 이 클래스가 순서를 조율한다.
 */
@Service
public class LifecycleTransactions {

    private final AccountCommands account;
    private final AccountDeletion deletion;
    private final GoogleAccessRevocation revocation;
    private final UserSessions sessions;
    private final SuggestionDataDeletion suggestionHealth;
    private final SuggestionCalendarDeletion suggestionCalendar;
    private final SafetyDataDeletion safety;
    private final CbiDataDeletion cbi;
    private final RecoveryDataDeletion recovery;
    private final CheckinDataDeletion checkin;
    private final CalendarDataDeletion calendar;
    private final ScheduleDataDeletion schedule;
    private final IdempotencyService idempotency;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public LifecycleTransactions(AccountCommands account, AccountDeletion deletion, GoogleAccessRevocation revocation,
            UserSessions sessions, SuggestionDataDeletion suggestionHealth,
            SuggestionCalendarDeletion suggestionCalendar, SafetyDataDeletion safety, CbiDataDeletion cbi,
            RecoveryDataDeletion recovery, CheckinDataDeletion checkin, CalendarDataDeletion calendar,
            ScheduleDataDeletion schedule, IdempotencyService idempotency) {
        this.account = account;
        this.deletion = deletion;
        this.revocation = revocation;
        this.sessions = sessions;
        this.suggestionHealth = suggestionHealth;
        this.suggestionCalendar = suggestionCalendar;
        this.safety = safety;
        this.cbi = cbi;
        this.recovery = recovery;
        this.checkin = checkin;
        this.calendar = calendar;
        this.schedule = schedule;
        this.idempotency = idempotency;
    }

    /** 건강 기록 삭제: 제안 근거 → 안전 안내 → CBI → 회복 → 측정·체크 → 재시도 응답 캐시 순서. */
    @Transactional
    public void eraseHealthData(long userId) {
        deletion.lock(userId);
        eraseHealthRecords(userId);
    }

    /** 건강정보 동의 철회(측정 꺼짐). {@code deleteData}면 같은 트랜잭션에서 건강 기록도 지운다. */
    @Transactional
    public void withdrawHealthConsent(long userId, boolean deleteData) {
        deletion.lock(userId);
        account.withdrawHealthConsent(userId);
        if (deleteData) {
            eraseHealthRecords(userId);
        }
    }

    /** 캘린더 연결 해제: 연결을 숨기고 제안의 일정 참조를 끊은 뒤 일정·분류 규칙·주간 부하를 지운다. */
    @Transactional
    public void disconnectCalendar(long userId) {
        deletion.lock(userId);
        revocation.requestRevoke(userId);
        deleteCalendarDerived(userId);
    }

    /**
     * 탈퇴 시작: 탈퇴 중으로 표시하고 구글 철회를 요청한 뒤, 구글 철회를 기다리지 않고 지울 수 있는 것을 모두 지운다
     * (건강 기록 → 일정·파생 → 로그인 세션). 구글 철회가 실패해 외부 정리가 남아도 이 시점부터 로그인 세션이 없고
     * 건강 기록·일정은 남지 않는다. 사용자 행과 철회에 필요한 암호화 토큰(그리고 사용자 행에 딸린 동의·설정·구독·
     * 승인 이력)은 철회가 끝난 뒤 {@link #finishWithdrawal}이 지운다. 다시 불러도 같은 결과다.
     */
    @Transactional
    public void beginWithdrawal(long userId) {
        deletion.lock(userId);
        deletion.markDeleting(userId);
        revocation.requestRevoke(userId);
        eraseHealthRecords(userId);
        deleteCalendarDerived(userId);
        sessions.revokeAll(userId);
    }

    /** 탈퇴 마무리(구글 철회가 끝난 뒤): 참조 해제 → 일정·파생 → 세션 → 사용자(나머지는 FK CASCADE). */
    @Transactional
    public void finishWithdrawal(long userId) {
        deletion.lock(userId);
        deleteCalendarDerived(userId);
        sessions.revokeAll(userId);
        deletion.deleteUser(userId);
    }

    private void eraseHealthRecords(long userId) {
        suggestionHealth.removeHealthBasis(userId);
        safety.deleteAll(userId);
        cbi.deleteAll(userId);
        recovery.deleteAll(userId);
        checkin.eraseHealthData(userId);
        idempotency.deleteForUser(userId, "hrv-");
        idempotency.deleteForUser(userId, "cbi-");
    }

    private void deleteCalendarDerived(long userId) {
        suggestionCalendar.removeCalendarBasis(userId);
        calendar.deleteAll(userId);
        schedule.deleteAll(userId);
    }
}
