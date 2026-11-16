package com.comedown.account.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * oauth_tokens.scopes에 Google 캘린더 범위가 있는지 읽고, 마지막 일정 동기화 시각을 읽고 쓴다(토큰 컬럼은 다루지 않는다).
 * 캘린더 범위는 {@code .../auth/calendar} 로 시작하므로 읽기·쓰기 범위 중 어느 쪽을 쓰기로 하든 같은 기준으로 판단한다.
 */
@Repository
public class CalendarConnectionReader {

    static final String CALENDAR_SCOPE_MARKER = "auth/calendar";

    private final JdbcClient jdbc;

    public CalendarConnectionReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasCalendarScope(long userId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM oauth_tokens WHERE user_id = ? AND LOCATE(?, scopes) > 0 AND revoke_requested_at IS NULL")
                .params(userId, CALENDAR_SCOPE_MARKER)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /** 연결된(철회 요청이 없는) 캘린더 연결 행에만 기록한다. */
    public void markSynced(long userId, Instant syncedAt) {
        jdbc.sql("UPDATE oauth_tokens SET calendar_synced_at = ? WHERE user_id = ? AND LOCATE(?, scopes) > 0 "
                        + "AND revoke_requested_at IS NULL")
                .params(LocalDateTime.ofInstant(syncedAt, ZoneOffset.UTC), userId, CALENDAR_SCOPE_MARKER)
                .update();
    }

    public Optional<Instant> lastSyncedAt(long userId) {
        return jdbc.sql("SELECT calendar_synced_at FROM oauth_tokens WHERE user_id = ? AND LOCATE(?, scopes) > 0 "
                        + "AND revoke_requested_at IS NULL AND calendar_synced_at IS NOT NULL")
                .params(userId, CALENDAR_SCOPE_MARKER)
                .query((rs, n) -> rs.getObject("calendar_synced_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))
                .optional();
    }
}
