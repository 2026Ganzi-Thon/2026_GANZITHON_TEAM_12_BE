package com.comedown.account.contract;

import java.time.Instant;
import java.util.Optional;

/**
 * 마지막 일정 동기화 시각. 캘린더 연결(oauth_tokens)에 붙은 값이라 account가 소유한다.
 * 일정이 0건인 동기화도 기록하고, 연결을 해제하면 사라진다.
 */
public interface CalendarSyncState {

    /** 동기화 저장이 끝난 시각을 기록한다. 연결된 상태가 아니면 아무것도 하지 않는다. */
    void markSynced(long userId, Instant syncedAt);

    /** 연결된 캘린더를 마지막으로 동기화한 시각. 동기화한 적이 없거나 연결되지 않았으면 empty. */
    Optional<Instant> lastSyncedAt(long userId);
}
