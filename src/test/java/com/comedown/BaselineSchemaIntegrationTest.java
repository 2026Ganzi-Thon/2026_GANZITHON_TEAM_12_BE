package com.comedown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MySqlContainerTestConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전체 애플리케이션을 MySQL 8.4 위에서 띄우고(Flyway → JPA validate → 보안·세션 설정),
 * ERD의 테이블·FK·UK·CHECK가 기준선 migration에 반영됐는지 검증한다.
 * <p>
 * 참고: MySQL CHECK 위반(오류 3819)은 Spring이 DataIntegrityViolationException으로 번역하지 않고
 * UncategorizedSQLException으로 던진다. 입력 범위는 DB에 의존하지 말고 요청 검증(400)에서 먼저 막는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
@Transactional // 같은 커넥션에서 LAST_INSERT_ID()를 읽고, 테스트 후 롤백한다.
class BaselineSchemaIntegrationTest {

    private static final List<String> DOMAIN_TABLES = List.of(
            "users", "user_consents", "user_settings", "user_preferred_activities", "oauth_tokens",
            "subscriptions", "cbi_assessments", "cbi_answers", "calendar_events", "event_category_overrides",
            "weekly_loads", "morning_checks", "hrv_measurements", "hrv_daily_states", "suggestions",
            "ai_outputs", "safety_alerts", "api_idempotency_keys");

    private static final String NOW = "'2026-11-11 22:30:00.000000'";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void ERD의_도메인_테이블_18개와_세션_테이블_2개가_생성된다() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);

        assertThat(tables).containsAll(DOMAIN_TABLES);
        assertThat(tables).contains("SPRING_SESSION", "SPRING_SESSION_ATTRIBUTES");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void 아침_체크는_사용자별_하루_한_번이고_컨디션은_1에서_5() {
        long user = insertUser("sub-1");
        insertMorningCheck(user, "2026-11-12");

        assertThatThrownBy(() -> insertMorningCheck(user, "2026-11-12"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO morning_checks (user_id, check_date, day_index, condition_score, created_at) "
                        + "VALUES (?, '2026-11-13', 2, 6, " + NOW + ")", user))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_morning_checks_condition");
    }

    @Test
    void HRV_측정은_같은_사용자의_아침_체크만_참조할_수_있다() {
        long me = insertUser("sub-me");
        long other = insertUser("sub-other");
        long othersCheck = insertMorningCheck(other, "2026-11-12");
        long myCheck = insertMorningCheck(me, "2026-11-12");

        insertMeasurement(me, myCheck, 42.0);
        assertThatThrownBy(() -> insertMeasurement(me, othersCheck, 42.0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMeasurement(me, myCheck, 0.0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_hrv_measurements_rmssd");
    }

    @Test
    void 재측정_필요_측정은_RMSSD_없이_사유와_함께_저장된다() {
        long user = insertUser("sub-retry");
        long check = insertMorningCheck(user, "2026-11-12");

        insertRetryMeasurement(user, check, "TOO_NOISY");
        assertThatThrownBy(() -> insertRetryMeasurement(user, check, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_hrv_measurements_retry_reason");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO hrv_measurements (user_id, morning_check_id, measured_at, "
                        + "source, quality_level, algorithm_version, created_at) VALUES (?, ?, " + NOW
                        + ", 'CAMERA', 'GOOD', 'hrv-cam-v1', " + NOW + ")", user, check))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_hrv_measurements_value_present");
    }

    @Test
    void CBI는_회차별_한_번이고_13개_문항_범위를_지킨다() {
        long user = insertUser("sub-cbi");
        long assessment = insertCbi(user, "INITIAL");

        assertThatThrownBy(() -> insertCbi(user, "INITIAL")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO cbi_answers VALUES (?, 13, 4, 100)", assessment);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO cbi_answers VALUES (?, 14, 0, 0)", assessment))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_cbi_answers_item_no");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO cbi_answers VALUES (?, 1, 2, 40)", assessment))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_cbi_answers_value");
    }

    @Test
    void 제안이_참조하는_일정은_참조를_먼저_해제해야_삭제된다() {
        long user = insertUser("sub-sg");
        long event = insertEvent(user, "g-1");
        long suggestion = insertSuggestion(user, event, "reg-1");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM calendar_events WHERE id = ?", event))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("UPDATE suggestions SET related_event_id = NULL WHERE id = ?", suggestion);
        assertThat(jdbc.update("DELETE FROM calendar_events WHERE id = ?", event)).isEqualTo(1);
    }

    @Test
    void 제안은_다른_사용자의_일정을_참조할_수_없고_등록_키는_유일하다() {
        long me = insertUser("sub-a");
        long other = insertUser("sub-b");
        long othersEvent = insertEvent(other, "g-other");

        assertThatThrownBy(() -> insertSuggestion(me, othersEvent, "reg-x"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertSuggestion(me, null, "reg-dup");
        assertThatThrownBy(() -> insertSuggestion(me, null, "reg-dup"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 주간_부하의_위험_사유는_기본_빈_배열() {
        long user = insertUser("sub-wl");
        jdbc.update("INSERT INTO weekly_loads (user_id, week_start, total_hours, load_score, deadline_count, "
                + "late_night_count, weekend_hours, rest_days, risk_level, calculation_version, computed_at) "
                + "VALUES (?, '2026-11-09', 10.0, 12.0, 0, 0, 0.0, 2, 'NONE', 'v0', " + NOW + ")", user);

        assertThat(jdbc.queryForObject("SELECT CAST(risk_reasons AS CHAR) FROM weekly_loads WHERE user_id = ?",
                String.class, user)).isEqualTo("[]");
    }

    @Test
    void 사용자를_삭제하면_건강_기록까지_함께_삭제된다() {
        long user = insertUser("sub-del");
        long check = insertMorningCheck(user, "2026-11-12");
        insertMeasurement(user, check, 50.0);
        long assessment = insertCbi(user, "INITIAL");
        jdbc.update("INSERT INTO cbi_answers VALUES (?, 1, 0, 0)", assessment);

        jdbc.update("DELETE FROM users WHERE id = ?", user);

        for (String table : List.of("morning_checks", "hrv_measurements", "cbi_assessments")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cbi_answers", Integer.class)).isZero();
    }

    // ---- fixtures ----

    private long insertUser(String googleSub) {
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, created_at, last_login_at) "
                + "VALUES (?, 'PROFILE', " + NOW + ", " + NOW + ")", googleSub);
        return lastId();
    }

    private long insertMorningCheck(long userId, String date) {
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, created_at) VALUES (?, ?, 1, "
                + NOW + ")", userId, date);
        return lastId();
    }

    private void insertMeasurement(long userId, long checkId, double rmssd) {
        jdbc.update("INSERT INTO hrv_measurements (user_id, morning_check_id, measured_at, source, rmssd_ms, "
                + "ln_rmssd, quality_level, algorithm_version, created_at) VALUES (?, ?, " + NOW
                + ", 'CAMERA', ?, ?, 'GOOD', 'hrv-cam-v1', " + NOW + ")",
                userId, checkId, rmssd, rmssd > 0 ? Math.log(rmssd) : 0);
    }

    private void insertRetryMeasurement(long userId, long checkId, String retryReason) {
        jdbc.update("INSERT INTO hrv_measurements (user_id, morning_check_id, measured_at, source, "
                + "quality_level, retry_reason, algorithm_version, created_at) VALUES (?, ?, " + NOW
                + ", 'CAMERA', 'RETRY', ?, 'hrv-cam-v1', " + NOW + ")", userId, checkId, retryReason);
    }

    private long insertCbi(long userId, String round) {
        jdbc.update("INSERT INTO cbi_assessments (user_id, round, questionnaire_version, personal_score, "
                + "work_score, high_risk, taken_at) VALUES (?, ?, 'v1', 50.00, 50.00, FALSE, " + NOW + ")",
                userId, round);
        return lastId();
    }

    private long insertEvent(long userId, String googleEventId) {
        jdbc.update("INSERT INTO calendar_events (user_id, google_event_id, start_at, end_at, all_day, "
                + "event_status, transparency, created_by_app, synced_at) VALUES (?, ?, "
                + "'2026-11-18 05:00:00', '2026-11-18 06:30:00', FALSE, 'confirmed', 'opaque', FALSE, " + NOW
                + ")", userId, googleEventId);
        return lastId();
    }

    private long insertSuggestion(long userId, Long relatedEventId, String registrationKey) {
        jdbc.update("INSERT INTO suggestions (user_id, type, status, target_date, start_at, end_at, title, "
                + "reason_code, calculation_version, registration_key, related_event_id, expires_at, created_at, "
                + "updated_at) VALUES (?, 'PRE_START', 'PROPOSED', '2026-11-12', '2026-11-12 07:00:00', "
                + "'2026-11-12 08:00:00', '발표 준비', 'RISK_WEEK', 'v0', ?, ?, '2026-11-12 15:00:00', " + NOW
                + ", " + NOW + ")", userId, registrationKey, relatedEventId);
        return lastId();
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
