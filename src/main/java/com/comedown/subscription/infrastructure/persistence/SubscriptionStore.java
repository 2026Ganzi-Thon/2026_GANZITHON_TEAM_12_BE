package com.comedown.subscription.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** subscriptions 읽기·쓰기(subscription 소유). DATETIME(6)은 UTC로 읽고 쓴다. */
@Repository
public class SubscriptionStore {

    public record Row(String plan, Instant trialEndsAt) {
    }

    private final JdbcClient jdbc;

    public SubscriptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(long userId) {
        return jdbc.sql("SELECT plan, trial_ends_at FROM subscriptions WHERE user_id = ?").params(userId)
                .query((rs, n) -> new Row(rs.getString("plan"),
                        rs.getObject("trial_ends_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                .optional();
    }

    /** 이미 있으면 건드리지 않는다. */
    public void insertIfAbsent(long userId, String plan, Instant startedAt, Instant endsAt, Instant now) {
        jdbc.sql("INSERT IGNORE INTO subscriptions (user_id, plan, trial_started_at, trial_ends_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)")
                .params(userId, plan, utc(startedAt), utc(endsAt), utc(now)).update();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
