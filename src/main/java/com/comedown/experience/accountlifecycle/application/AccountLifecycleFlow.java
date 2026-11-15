package com.comedown.experience.accountlifecycle.application;

import com.comedown.account.contract.AccountDeletion;
import com.comedown.account.contract.GoogleAccessRevocation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 연결 해제·탈퇴·건강 기록 삭제 흐름. 이 클래스 자체는 트랜잭션이 아니다: DB 단계는 {@link LifecycleTransactions}에서
 * 각각 한 트랜잭션으로 끝내고, 구글 호출은 그 사이에 트랜잭션 밖에서 한다. 구글 철회가 실패하면 상태(철회 요청·탈퇴 중)가
 * DB에 남아 있으므로 같은 API를 다시 부르거나 재시도 작업이 이어서 끝낸다.
 */
@Service
public class AccountLifecycleFlow {

    private static final Logger log = LoggerFactory.getLogger(AccountLifecycleFlow.class);
    static final int RETRY_BATCH = 50;

    /** DONE이면 정리가 끝났다(204), PENDING이면 외부 정리가 남았다(202). */
    public enum Outcome { DONE, PENDING }

    private final LifecycleTransactions transactions;
    private final GoogleAccessRevocation revocation;
    private final AccountDeletion deletion;

    public AccountLifecycleFlow(LifecycleTransactions transactions, GoogleAccessRevocation revocation,
            AccountDeletion deletion) {
        this.transactions = transactions;
        this.revocation = revocation;
        this.deletion = deletion;
    }

    public void eraseHealthData(long userId) {
        transactions.eraseHealthData(userId);
    }

    public void withdrawHealthConsent(long userId, boolean deleteData) {
        transactions.withdrawHealthConsent(userId, deleteData);
    }

    public Outcome disconnectCalendar(long userId) {
        transactions.disconnectCalendar(userId);
        return revocation.revokePending(userId) ? Outcome.DONE : Outcome.PENDING;
    }

    public Outcome withdraw(long userId) {
        transactions.beginWithdrawal(userId);
        return completeWithdrawal(userId);
    }

    /** 구글 철회 → 끝났으면 로컬 정리·사용자 삭제. 재시도 작업도 이 메서드로 이어서 끝낸다. */
    Outcome completeWithdrawal(long userId) {
        if (!revocation.revokePending(userId)) {
            return Outcome.PENDING;
        }
        transactions.finishWithdrawal(userId);
        return Outcome.DONE;
    }

    /** 끝나지 않은 탈퇴·철회를 이어서 처리한다(재시도 작업). */
    public void retryPending() {
        Set<Long> deleting = new LinkedHashSet<>(deletion.deletingUsers(RETRY_BATCH));
        for (long userId : deleting) {
            runSafely(userId, () -> completeWithdrawal(userId));
        }
        List<Long> pending = revocation.pendingUsers(RETRY_BATCH);
        for (long userId : pending) {
            if (!deleting.contains(userId)) {
                runSafely(userId, () -> revocation.revokePending(userId));
            }
        }
    }

    private void runSafely(long userId, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.warn("정리 재시도 실패(userId={}): {}", userId, e.getClass().getSimpleName());
        }
    }
}
