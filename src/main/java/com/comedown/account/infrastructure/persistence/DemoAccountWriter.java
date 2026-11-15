package com.comedown.account.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * local 전용 시연 계정 상태 쓰기(users·user_settings·user_consents). DATETIME(6)에는 UTC 값을 쓴다.
 */
@Repository
@Profile("local")
public class DemoAccountWriter {

    static final String DEMO_CONSENT_VERSION = "dev-seed";

    private final JdbcClient jdbc;

    public DemoAccountWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 바뀐 행 수(사용자가 없으면 0) */
    public int completeOnboarding(long userId, Instant completedAt) {
        return jdbc.sql("UPDATE users SET onboarding_step = 'DONE', onboarding_completed_at = ? WHERE id = ?")
                .params(utc(completedAt), userId)
                .update();
    }

    public void enableHrv(long userId, Instant now) {
        jdbc.sql("""
                        INSERT INTO user_settings (user_id, hrv_enabled, updated_at) VALUES (?, TRUE, ?) AS v
                        ON DUPLICATE KEY UPDATE hrv_enabled = TRUE, updated_at = v.updated_at
                        """)
                .params(userId, utc(now))
                .update();
    }

    /** 가장 최근 건강정보 동의가 없거나 철회됐으면 새 동의를 넣는다. */
    public void ensureHrvConsent(long userId, Instant now) {
        Boolean valid = jdbc.sql("""
                        SELECT c.withdrawn_at IS NULL
                          FROM user_consents c
                         WHERE c.user_id = ? AND c.consent_type = ?
                         ORDER BY c.agreed_at DESC, c.id DESC
                         LIMIT 1
                        """)
                .params(userId, AccountAccessReader.SENSITIVE_HRV)
                .query(Boolean.class)
                .optional()
                .orElse(false);
        if (!valid) {
            jdbc.sql("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, ?, ?, ?)")
                    .params(userId, AccountAccessReader.SENSITIVE_HRV, DEMO_CONSENT_VERSION, utc(now))
                    .update();
        }
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
