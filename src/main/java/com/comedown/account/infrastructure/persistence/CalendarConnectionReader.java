package com.comedown.account.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * oauth_tokens.scopes에 Google 캘린더 범위가 있는지 읽는다(읽기 전용, 토큰 컬럼은 조회하지 않는다).
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
}
