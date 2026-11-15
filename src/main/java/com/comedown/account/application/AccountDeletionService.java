package com.comedown.account.application;

import com.comedown.account.contract.AccountDeletion;
import com.comedown.account.infrastructure.persistence.AccountStore;
import com.comedown.shared.time.ServiceTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 탈퇴 상태 전이. 정리 순서는 experience.accountlifecycle이 조율한다. */
@Service
public class AccountDeletionService implements AccountDeletion {

    private final AccountStore store;
    private final ServiceTime time;

    public AccountDeletionService(AccountStore store, ServiceTime time) {
        this.store = store;
        this.time = time;
    }

    @Override
    @Transactional
    public void lock(long userId) {
        store.lockUser(userId);
    }

    @Override
    @Transactional
    public void markDeleting(long userId) {
        store.markDeleting(userId, time.now());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> deletingUsers(int limit) {
        return store.deletingUsers(limit);
    }

    @Override
    @Transactional
    public void deleteUser(long userId) {
        store.deleteUser(userId);
    }
}
