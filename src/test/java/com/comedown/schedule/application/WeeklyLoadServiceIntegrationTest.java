package com.comedown.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.comedown.schedule.domain.WeekLoad;
import com.comedown.schedule.domain.WeekLoad.RiskLevel;
import com.comedown.schedule.domain.WeekLoad.RiskReason;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
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

    @Autowired
    MutableClock clock;

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
    void 직전_주가_거의_비어_있어도_평소_대비를_컬럼_범위_안으로_저장한다() {
        // 직전 두 주에 6분짜리 수업 하나씩(부하 0.1) → 평균 0.05. 이번 주는 평일 낮 11시간씩 55시간.
        insertEvent(userId, "p1", "2026-10-26 01:00:00", "2026-10-26 01:06:00", false, "CLASS", null);
        insertEvent(userId, "p2", "2026-11-02 01:00:00", "2026-11-02 01:06:00", false, "CLASS", null);
        for (int day = 0; day < 5; day++) {
            insertEvent(userId, "b" + day, "2026-11-" + pad(9 + day) + " 00:00:00",
                    "2026-11-" + pad(9 + day) + " 11:00:00", false, "CLASS", null);
        }

        service.recalculate(userId, List.of(MON.minusWeeks(2), MON.minusWeeks(1), MON));

        // (55.0 ÷ 0.05 − 1) × 100 = 109900.0은 DECIMAL(6,1)에 들어가지 않는다. 저장이 실패하지 않고 한계값으로 남는다.
        WeekLoad load = service.weeks(userId, MON, 1).get(0);
        assertThat(load.loadScore()).isEqualByComparingTo("55.0");
        assertThat(load.vsUsualPct()).isEqualByComparingTo("99999.9");
        assertThat(jdbc.queryForObject("SELECT vs_usual_pct FROM weekly_loads WHERE user_id = ? AND week_start = ?",
                java.math.BigDecimal.class, userId, MON)).isEqualByComparingTo("99999.9");
    }

    @Test
    void 마지막_계산_시각은_요청한_주_범위의_행만_본다() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        service.recalculate(userId, List.of(MON.minusWeeks(1), MON));

        assertThat(service.lastComputedAt(userId, MON, 5)).contains(java.time.Instant.parse("2026-11-11T22:30:00Z"));
        assertThat(service.lastComputedAt(userId, MON.plusWeeks(1), 4)).isEmpty();
        assertThat(service.lastComputedAt(otherUserId, MON, 5)).isEmpty();
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

    private static String pad(int day) {
        return day < 10 ? "0" + day : String.valueOf(day);
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
