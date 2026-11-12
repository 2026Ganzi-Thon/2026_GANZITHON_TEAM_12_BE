package com.comedown.shared.idempotency;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * api_idempotency_keys 저장소. 시각은 UTC LocalDateTime으로 변환해 DATETIME(6)에 그대로 저장한다
 * (JDBC 드라이버의 시간대 변환에 의존하지 않음).
 */
@Repository
class IdempotencyRepository {

    static final String PROCESSING = "PROCESSING";
    static final String COMPLETED = "COMPLETED";

    private final JdbcClient jdbc;

    IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(long id, String requestHash, String status, Integer responseStatus, String responseBody,
               Instant expiresAt) {
    }

    long insertProcessing(long userId, String operation, String requestKey, String requestHash,
            Instant now, Instant expiresAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO api_idempotency_keys
                            (user_id, operation, request_key, request_hash, status, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(userId, operation, requestKey, requestHash, PROCESSING, utc(now), utc(expiresAt))
                .update(keyHolder, "id");
        return keyHolder.getKeyAs(Number.class).longValue();
    }

    Optional<Row> find(long userId, String operation, String requestKey) {
        return jdbc.sql("""
                        SELECT id, request_hash, status, response_status, CAST(response_body AS CHAR) AS body, expires_at
                        FROM api_idempotency_keys
                        WHERE user_id = ? AND operation = ? AND request_key = ?
                        """)
                .params(userId, operation, requestKey)
                .query((rs, n) -> new Row(
                        rs.getLong("id"),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        (Integer) rs.getObject("response_status", Integer.class),
                        rs.getString("body"),
                        rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                .optional();
    }

    int complete(long id, int responseStatus, String responseBody) {
        return jdbc.sql("""
                        UPDATE api_idempotency_keys
                        SET status = ?, response_status = ?, response_body = ?
                        WHERE id = ? AND status = ?
                        """)
                .params(COMPLETED, responseStatus, responseBody, id, PROCESSING)
                .update();
    }

    int deleteProcessing(long id) {
        return jdbc.sql("DELETE FROM api_idempotency_keys WHERE id = ? AND status = ?")
                .params(id, PROCESSING)
                .update();
    }

    int deleteExpiredCompleted(long id, Instant now) {
        return jdbc.sql("DELETE FROM api_idempotency_keys WHERE id = ? AND status = ? AND expires_at <= ?")
                .params(id, COMPLETED, utc(now))
                .update();
    }

    int deleteAllExpiredCompleted(Instant now) {
        return jdbc.sql("DELETE FROM api_idempotency_keys WHERE status = ? AND expires_at <= ?")
                .params(COMPLETED, utc(now))
                .update();
    }

    int deleteByUser(long userId) {
        return jdbc.sql("DELETE FROM api_idempotency_keys WHERE user_id = ?")
                .params(userId)
                .update();
    }

    int deleteByUserAndOperationPrefix(long userId, String operationPrefix) {
        return jdbc.sql("DELETE FROM api_idempotency_keys WHERE user_id = ? AND operation LIKE ? ESCAPE '!'")
                .params(userId, escapeLike(operationPrefix) + "%")
                .update();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
