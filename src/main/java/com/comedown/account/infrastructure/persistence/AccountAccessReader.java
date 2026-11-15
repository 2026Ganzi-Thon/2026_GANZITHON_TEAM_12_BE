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
}
