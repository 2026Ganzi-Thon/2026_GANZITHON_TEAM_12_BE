package com.comedown.account.application;

import com.comedown.account.contract.CalendarSyncState;
import com.comedown.account.infrastructure.persistence.CalendarConnectionReader;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CalendarSyncStateService implements CalendarSyncState {

    private final CalendarConnectionReader connections;

    CalendarSyncStateService(CalendarConnectionReader connections) {
        this.connections = connections;
    }

    @Override
    @Transactional
    public void markSynced(long userId, Instant syncedAt) {
        connections.markSynced(userId, syncedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastSyncedAt(long userId) {
        return connections.lastSyncedAt(userId);
    }
}
