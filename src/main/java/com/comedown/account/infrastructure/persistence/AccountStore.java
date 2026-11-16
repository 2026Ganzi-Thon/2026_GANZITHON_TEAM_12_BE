package com.comedown.account.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** users·user_consents·user_settings·user_preferred_activities 읽기·쓰기(account 소유). DATETIME(6)은 UTC. */
@Repository
public class AccountStore {

    public record UserRow(long id, String email, String nickname, String onboardingStep, Instant completedAt) {
    }

    public record ConsentRow(String type, String version, Instant agreedAt, Instant withdrawnAt) {
        public boolean active() {
            return withdrawnAt == null;
        }
    }

    public record SettingsRow(LocalTime measureTime, LocalTime sleepStart, LocalTime sleepEnd, boolean hrvEnabled) {
    }

    private final JdbcClient jdbc;

    public AccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findUser(long userId) {
        return jdbc.sql("SELECT id, email, nickname, onboarding_step, onboarding_completed_at FROM users WHERE id = ?")
                .params(userId)
                .query((rs, n) -> new UserRow(rs.getLong("id"), rs.getString("email"), rs.getString("nickname"),
                        rs.getString("onboarding_step"), instant(rs.getObject("onboarding_completed_at",
                        LocalDateTime.class))))
                .optional();
    }

    /** 해당 사용자의 구글 계정 식별자(sub)가 이 값인지. */
    public boolean hasGoogleSub(long userId, String googleSub) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM users WHERE id = ? AND google_sub = ?")
                .params(userId, googleSub).query(Integer.class).single();
        return n != null && n > 0;
    }

    public int updateNickname(long userId, String nickname) {
        return jdbc.sql("UPDATE users SET nickname = ? WHERE id = ?").params(nickname, userId).update();
    }

    /** 타입별 가장 최근 동의 한 건씩. */
    public Map<String, ConsentRow> latestConsents(long userId) {
        List<ConsentRow> rows = jdbc.sql("""
                        SELECT c.consent_type, c.version, c.agreed_at, c.withdrawn_at
                          FROM user_consents c
                         WHERE c.user_id = ?
                           AND c.id = (SELECT c2.id FROM user_consents c2
                                        WHERE c2.user_id = c.user_id AND c2.consent_type = c.consent_type
                                        ORDER BY c2.agreed_at DESC, c2.id DESC LIMIT 1)
                         ORDER BY c.consent_type
                        """)
                .params(userId)
                .query((rs, n) -> new ConsentRow(rs.getString("consent_type"), rs.getString("version"),
                        instant(rs.getObject("agreed_at", LocalDateTime.class)),
                        instant(rs.getObject("withdrawn_at", LocalDateTime.class))))
                .list();
        Map<String, ConsentRow> byType = new LinkedHashMap<>();
        rows.forEach(r -> byType.put(r.type(), r));
        return byType;
    }

    public void insertConsent(long userId, String type, String version, Instant now) {
        jdbc.sql("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, ?, ?, ?)")
                .params(userId, type, version, utc(now)).update();
    }

    public Optional<SettingsRow> findSettings(long userId) {
        return jdbc.sql("SELECT measure_time, sleep_start, sleep_end, hrv_enabled FROM user_settings WHERE user_id = ?")
                .params(userId)
                .query((rs, n) -> new SettingsRow(rs.getObject("measure_time", LocalTime.class),
                        rs.getObject("sleep_start", LocalTime.class), rs.getObject("sleep_end", LocalTime.class),
                        rs.getBoolean("hrv_enabled")))
                .optional();
    }

    public List<String> preferredActivities(long userId) {
        return jdbc.sql("SELECT activity_code FROM user_preferred_activities WHERE user_id = ? ORDER BY activity_code")
                .params(userId).query(String.class).list();
    }

    /** 생활 정보 4필드 저장(hrv_enabled는 그대로). */
    public void upsertLifestyle(long userId, LocalTime measureTime, LocalTime sleepStart, LocalTime sleepEnd,
            Instant now) {
        jdbc.sql("""
                        INSERT INTO user_settings (user_id, measure_time, sleep_start, sleep_end, updated_at)
                        VALUES (?, ?, ?, ?, ?) AS v
                        ON DUPLICATE KEY UPDATE measure_time = v.measure_time, sleep_start = v.sleep_start,
                                                sleep_end = v.sleep_end, updated_at = v.updated_at
                        """)
                .params(userId, measureTime, sleepStart, sleepEnd, utc(now)).update();
    }

    public void replaceActivities(long userId, List<String> codes) {
        jdbc.sql("DELETE FROM user_preferred_activities WHERE user_id = ?").params(userId).update();
        for (String code : codes) {
            jdbc.sql("INSERT INTO user_preferred_activities (user_id, activity_code) VALUES (?, ?)")
                    .params(userId, code).update();
        }
    }

    public void setHrvEnabled(long userId, boolean enabled, Instant now) {
        jdbc.sql("""
                        INSERT INTO user_settings (user_id, hrv_enabled, updated_at) VALUES (?, ?, ?) AS v
                        ON DUPLICATE KEY UPDATE hrv_enabled = v.hrv_enabled, updated_at = v.updated_at
                        """)
                .params(userId, enabled, utc(now)).update();
    }

    /** 사용자 행을 잠근다(트랜잭션 안에서). 없는 사용자는 false. */
    public boolean lockUser(long userId) {
        return jdbc.sql("SELECT id FROM users WHERE id = ? FOR UPDATE").params(userId).query(Long.class)
                .optional().isPresent();
    }

    public void markDeleting(long userId, Instant now) {
        jdbc.sql("UPDATE users SET deletion_status = 'DELETING', deletion_requested_at = ? "
                        + "WHERE id = ? AND deletion_status = 'ACTIVE'")
                .params(utc(now), userId).update();
    }

    /**
     * 탈퇴 진행 중인 사용자(재시도 대상). 구글 철회가 남은 사용자는 마지막 철회 시도 시각(oauth_tokens.updated_at),
     * 나머지는 탈퇴 요청 시각 순서다. 철회가 계속 실패하는 사용자가 뒤로 밀려 다른 사용자를 막지 않는다.
     */
    public List<Long> deletingUsers(int limit) {
        return jdbc.sql("""
                        SELECT u.id
                          FROM users u
                          LEFT JOIN oauth_tokens t ON t.user_id = u.id AND t.revoke_requested_at IS NOT NULL
                         WHERE u.deletion_status = 'DELETING'
                         ORDER BY COALESCE(t.updated_at, u.deletion_requested_at), u.id
                         LIMIT ?
                        """)
                .params(limit).query(Long.class).list();
    }

    public void deleteUser(long userId) {
        jdbc.sql("DELETE FROM users WHERE id = ?").params(userId).update();
    }

    /** 가장 최근 동의가 유효하면 철회 시각을 기록한다. */
    public void withdrawLatestConsent(long userId, String type, Instant now) {
        jdbc.sql("""
                        UPDATE user_consents SET withdrawn_at = ?
                         WHERE user_id = ? AND consent_type = ? AND withdrawn_at IS NULL
                        """)
                .params(utc(now), userId, type).update();
    }

    public boolean hasToken(long userId) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM oauth_tokens WHERE user_id = ? AND revoke_requested_at IS NULL").params(userId)
                .query(Integer.class).single();
        return n != null && n > 0;
    }

    private static Instant instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
