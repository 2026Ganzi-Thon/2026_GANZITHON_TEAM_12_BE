package com.comedown.account.infrastructure.persistence;

import com.comedown.account.contract.HealthDataAccess;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * users·user_settings·user_consents에서 건강정보 입력 가능 여부를 읽는다(읽기 전용).
 * DATETIME(6)은 UTC로 저장된 값으로 읽는다.
 */
@Repository
public class AccountAccessReader {

    static final String SENSITIVE_HRV = "SENSITIVE_HRV";
    public static final String CALENDAR = "CALENDAR";
    public static final String AI_NOTICE = "AI_NOTICE";
    static final String ACTIVE = "ACTIVE";

    private final JdbcClient jdbc;

    public AccountAccessReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<HealthDataAccess> find(long userId) {
        return jdbc.sql("""
                        SELECT u.id,
                               u.onboarding_step,
                               u.onboarding_completed_at,
                               u.deletion_status,
                               COALESCE(s.hrv_enabled, FALSE) AS hrv_enabled,
                               (SELECT c.withdrawn_at IS NULL
                                  FROM user_consents c
                                 WHERE c.user_id = u.id AND c.consent_type = ?
                                 ORDER BY c.agreed_at DESC, c.id DESC
                                 LIMIT 1) AS hrv_consented
                          FROM users u
                          LEFT JOIN user_settings s ON s.user_id = u.id
                         WHERE u.id = ?
                        """)
                .params(SENSITIVE_HRV, userId)
                .query((rs, n) -> {
                    LocalDateTime completed = rs.getObject("onboarding_completed_at", LocalDateTime.class);
                    return new HealthDataAccess(
                            rs.getLong("id"),
                            rs.getString("onboarding_step"),
                            completed == null ? null : completed.toInstant(ZoneOffset.UTC),
                            rs.getBoolean("hrv_consented"),
                            rs.getBoolean("hrv_enabled"),
                            ACTIVE.equals(rs.getString("deletion_status")));
                })
                .optional();
    }

    /** 그 종류의 가장 최근 동의 행(agreed_at, id 순)이 철회되지 않았는지. 동의 행이 없으면 false. */
    public boolean hasActiveConsent(long userId, String consentType) {
        return jdbc.sql("""
                        SELECT c.withdrawn_at IS NULL
                          FROM user_consents c
                         WHERE c.user_id = ? AND c.consent_type = ?
                         ORDER BY c.agreed_at DESC, c.id DESC
                         LIMIT 1
                        """)
                .params(userId, consentType)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    public Optional<String> findNickname(long userId) {
        return jdbc.sql("SELECT nickname FROM users WHERE id = ? AND nickname IS NOT NULL")
                .params(userId)
                .query(String.class)
                .optional();
    }
}
