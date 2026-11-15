package com.comedown.recovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 회복 재계산 저장(hrv_daily_states)을 MySQL 위에서 검증한다. 입력은 checkin 테이블에 직접 넣는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class RecoveryServiceIntegrationTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);

    @Autowired
    RecoveryService recovery;

    @Autowired
    JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void setUp() {
        String sub = "recovery-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, created_at, last_login_at) "
                + "VALUES (?, 'DONE', '2026-11-01 00:00:00', '2026-11-01 00:00:00')", sub);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'recovery-%'");
    }

    @Test
    void 지난_날짜부터_다시_계산하면_그날_이후_행을_새로_쓰고_기록이_없어진_날의_행은_지운다() {
        double[] ln = {3.60, 3.90, 3.70, 3.80, 3.65, 3.85, 3.75};
        for (int i = 0; i < ln.length; i++) {
            seedEligibleDay(D1.plusDays(i), ln[i]);
        }
        recovery.recalculate(userId, D1, D1.plusDays(6));
        assertThat(rowDates()).hasSize(7);
        assertThat(recovery.findDay(userId, D1.plusDays(6)))
                .get().extracting(DailyRecoverySnapshot::baselineEstablished).isEqualTo(true);

        // 11-04 기록 삭제 → 그날부터 다시 계산: 행이 사라지고 적격일이 6일이라 기준선 미확정
        jdbc.update("DELETE FROM morning_checks WHERE user_id = ? AND check_date = ?", userId, D1.plusDays(2));
        recovery.recalculate(userId, D1.plusDays(2), D1.plusDays(6));

        assertThat(rowDates()).hasSize(6).doesNotContain(D1.plusDays(2));
        DailyRecoverySnapshot last = recovery.findDay(userId, D1.plusDays(6)).orElseThrow();
        assertThat(last.baselineEstablished()).isFalse();
        assertThat(last.baselineValidDays()).isEqualTo(6);
        assertThat(last.recoveryStatus()).isEqualTo("BUILDING");
        // from 이전 행은 그대로 둔다
        assertThat(recovery.findDay(userId, D1).orElseThrow().baselineValidDays()).isEqualTo(1);
    }

    @Test
    void 대표_측정은_첫_적격_측정이다() {
        long checkId = seedEligibleDay(D1, 3.7);
        insertMeasurement(checkId, D1 + " 00:10:00", "GOOD", 3.9);

        long firstId = jdbc.queryForObject("SELECT MIN(id) FROM hrv_measurements WHERE user_id = ?", Long.class,
                userId);
        assertThat(recovery.findRepresentativeMeasurementId(userId, D1)).contains(firstId);
        assertThat(recovery.findRepresentativeMeasurementId(userId, D1.plusDays(1))).isEmpty();
    }

    private List<LocalDate> rowDates() {
        return jdbc.queryForList("SELECT state_date FROM hrv_daily_states WHERE user_id = ? ORDER BY state_date",
                LocalDate.class, userId);
    }

    private long seedEligibleDay(LocalDate date, double ln) {
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, condition_score, alcohol, sick, "
                + "caffeine, completed_at, created_at) VALUES (?, ?, 1, 3, FALSE, FALSE, FALSE, ?, ?)",
                userId, date, date + " 00:30:00", date + " 00:00:00");
        long checkId = jdbc.queryForObject("SELECT id FROM morning_checks WHERE user_id = ? AND check_date = ?",
                Long.class, userId, date);
        insertMeasurement(checkId, date.minusDays(1) + " 22:00:00", "GOOD", ln);
        return checkId;
    }

    private void insertMeasurement(long checkId, String measuredAtUtc, String quality, double ln) {
        jdbc.update("INSERT INTO hrv_measurements (user_id, morning_check_id, measured_at, source, rmssd_ms, "
                + "ln_rmssd, quality_level, algorithm_version, created_at) VALUES (?, ?, ?, 'CAMERA', ?, ?, ?, "
                + "'hrv-cam-v1', '2026-11-12 00:00:00')", userId, checkId, measuredAtUtc, Math.exp(ln), ln, quality);
    }
}
