package com.comedown.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.comedown.schedule.domain.WeekLoad;
import com.comedown.schedule.domain.WeekLoad.RiskLevel;
import com.comedown.schedule.domain.WeekLoad.RiskReason;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** MySQL 위에서 calendar_events 읽기 → 부하 계산 → weekly_loads 저장·조회를 끝까지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
@Transactional
class WeeklyLoadServiceIntegrationTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 9);

    @Autowired
    WeeklyLoadService service;

    @Autowired
    JdbcTemplate jdbc;

    long userId;
    long otherUserId;

    @BeforeEach
    void setUp() {
        userId = insertUser("sub-load-1");
        otherUserId = insertUser("sub-load-2");
    }

    @Test
    void 저장된_일정으로_주간_부하를_계산해_저장하고_조회한다() {
        // 월요일(서울) 10~12시 수업 = UTC 01~03시, 종일 마감 과제(화요일)
        insertEvent(userId, "g1", "2026-11-09 01:00:00", "2026-11-09 03:00:00", false, "CLASS", null);
        insertEvent(userId, "g2", "2026-11-09 15:00:00", "2026-11-10 15:00:00", true, "ASSIGNMENT", true);

        List<WeekLoad> calculated = service.recalculate(userId, List.of(MON));

        assertThat(calculated).hasSize(1);
        WeekLoad load = service.weeks(userId, MON, 1).get(0);
        assertThat(load.weekStart()).isEqualTo(MON);
        assertThat(load.totalHours()).isEqualByComparingTo("2.0");
        assertThat(load.loadScore()).isEqualByComparingTo("2.0");
        assertThat(load.deadlineCount()).isEqualTo(1);
        assertThat(load.restDays()).isEqualTo(6);
        assertThat(load.riskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(load.calculationVersion()).isEqualTo("load-v1");
        assertThat(jdbc.queryForObject("SELECT computed_at FROM weekly_loads WHERE user_id = ?",
                java.sql.Timestamp.class, userId)).isNotNull();
    }

    @Test
    void 위험_사유는_JSON_배열로_저장되고_그대로_복원된다() {
        for (int day = 0; day < 7; day++) {
            insertEvent(userId, "d" + day, "2026-11-" + (9 + day) + " 01:00:00",
                    "2026-11-" + (9 + day) + " 02:00:00", false, "CLASS", null);
        }

        service.recalculate(userId, List.of(MON));

        assertThat(service.weeks(userId, MON, 1).get(0).riskReasons()).containsExactly(RiskReason.NO_REST_DAY);
        assertThat(jdbc.queryForObject("SELECT JSON_LENGTH(risk_reasons) FROM weekly_loads WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void 다시_계산하면_같은_행을_갱신한다() {
        insertEvent(userId, "g1", "2026-11-09 01:00:00", "2026-11-09 03:00:00", false, "CLASS", null);
        service.recalculate(userId, List.of(MON));
        insertEvent(userId, "g2", "2026-11-10 01:00:00", "2026-11-10 03:00:00", false, "CLASS", null);

        service.recalculate(userId, List.of(MON));

        // weeks()의 JPQL 조회가 flush를 일으킨 뒤에 JDBC로 행 수를 센다.
        assertThat(service.weeks(userId, MON, 1).get(0).loadScore()).isEqualByComparingTo("4.0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_loads WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void 다른_사용자의_일정과_부하는_섞이지_않는다() {
        insertEvent(otherUserId, "o1", "2026-11-09 01:00:00", "2026-11-09 06:00:00", false, "EXAM", null);

        service.recalculate(userId, List.of(MON));

        WeekLoad mine = service.weeks(userId, MON, 1).get(0);
        assertThat(mine.loadScore()).isEqualByComparingTo("0");
        assertThat(service.weeks(otherUserId, MON, 1)).isEmpty();
    }

    @Test
    void 앱이_만든_일정_한가함_일정_개인_카테고리는_부하에서_제외된다() {
        insertEvent(userId, "x1", "2026-11-09 01:00:00", "2026-11-09 03:00:00", false, "CLASS", null, true, "opaque");
        insertEvent(userId, "x2", "2026-11-09 01:00:00", "2026-11-09 03:00:00", false, "CLASS", null, false, "transparent");
        insertEvent(userId, "x3", "2026-11-09 01:00:00", "2026-11-09 03:00:00", false, "PERSONAL", null);

        service.recalculate(userId, List.of(MON));

        assertThat(service.weeks(userId, MON, 1).get(0).totalHours()).isEqualByComparingTo("0");
    }

    private long insertUser(String sub) {
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, created_at, last_login_at) "
                + "VALUES (?, 'PROFILE', '2026-11-11 22:30:00.000000', '2026-11-11 22:30:00.000000')", sub);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertEvent(long user, String googleId, String startUtc, String endUtc, boolean allDay,
            String category, Boolean deadline) {
        insertEvent(user, googleId, startUtc, endUtc, allDay, category, deadline, false, "opaque");
    }

    private void insertEvent(long user, String googleId, String startUtc, String endUtc, boolean allDay,
            String category, Boolean deadline, boolean createdByApp, String transparency) {
        jdbc.update("INSERT INTO calendar_events (user_id, google_event_id, start_at, end_at, all_day, event_status, "
                        + "transparency, created_by_app, category, is_deadline, synced_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'confirmed', ?, ?, ?, ?, '2026-11-11 22:30:00.000000')",
                user, googleId, startUtc, endUtc, allDay, transparency, createdByApp, category, deadline);
    }
}
