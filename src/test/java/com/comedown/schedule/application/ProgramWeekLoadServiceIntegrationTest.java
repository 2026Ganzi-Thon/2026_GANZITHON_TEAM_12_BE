package com.comedown.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.schedule.contract.ProgramWeekLoad;
import com.comedown.schedule.contract.ProgramWeekLoadQuery;
import com.comedown.schedule.contract.ProgramWeekLoadRange;
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

/** MySQL 위에서 캘린더 연결 판정(oauth_tokens)과 n주차 부하 계산을 끝까지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
@Transactional
class ProgramWeekLoadServiceIntegrationTest {

    private static final LocalDate ONBOARDING = LocalDate.of(2026, 11, 4);
    private static final String CALENDAR_SCOPES =
            "openid email profile https://www.googleapis.com/auth/calendar.events";

    @Autowired
    ProgramWeekLoadQuery query;

    @Autowired
    CalendarConnectionQuery connection;

    @Autowired
    JdbcTemplate jdbc;

    long userId;

    @BeforeEach
    void setUp() {
        userId = insertUser("sub-program-1");
    }

    @Test
    void 캘린더_범위가_있는_토큰이_있으면_연결된_것으로_본다() {
        insertToken(userId, "google-calendar", "p-1", CALENDAR_SCOPES);

        assertThat(connection.isConnected(userId)).isTrue();
    }

    @Test
    void 로그인_범위만_있는_토큰이나_토큰이_없으면_연결되지_않은_것() {
        assertThat(connection.isConnected(userId)).isFalse();

        insertToken(userId, "google", "p-2", "openid email profile");

        assertThat(connection.isConnected(userId)).isFalse();
    }

    @Test
    void 다른_사용자의_토큰은_연결로_보지_않는다() {
        long other = insertUser("sub-program-2");
        insertToken(other, "google-calendar", "p-3", CALENDAR_SCOPES);

        assertThat(connection.isConnected(userId)).isFalse();
        assertThat(query.find(userId, ONBOARDING, 1, 1)).isEmpty();
    }

    @Test
    void 연결된_사용자는_저장된_일정으로_n주차_부하를_받는다() {
        insertToken(userId, "google-calendar", "p-4", CALENDAR_SCOPES);
        // 1주차(11/4~11/10) 수요일(서울) 10~12시 수업 = UTC 01~03시, 2주차 목요일(11/12) 9~10시
        insertEvent(userId, "e1", "2026-11-04 01:00:00", "2026-11-04 03:00:00");
        insertEvent(userId, "e2", "2026-11-12 00:00:00", "2026-11-12 01:00:00");

        List<ProgramWeekLoad> weeks = query.find(userId, ONBOARDING, 1, 2).orElseThrow();

        assertThat(weeks).extracting(ProgramWeekLoad::week).containsExactly(1, 2);
        assertThat(weeks.get(0).loadScore()).isEqualByComparingTo("2.0");
        assertThat(weeks.get(1).loadScore()).isEqualByComparingTo("1.0");
        assertThat(weeks.get(0).vsUsualUnavailableReason()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void 다른_사용자의_일정은_부하에_섞이지_않는다() {
        insertToken(userId, "google-calendar", "p-5", CALENDAR_SCOPES);
        long other = insertUser("sub-program-3");
        insertEvent(other, "o1", "2026-11-04 01:00:00", "2026-11-04 09:00:00");

        assertThat(query.find(userId, ONBOARDING, 1, 1).orElseThrow().get(0).loadScore()).isEqualByComparingTo("0");
    }

    @Test
    void 내_범위_위치는_1주차부터_진행_중인_주차까지의_부하로_계산한다() {
        insertToken(userId, "google-calendar", "p-6", CALENDAR_SCOPES);
        // 각 주차 수요일(서울) 10시부터 1·4·3·2시간 수업 → 부하 1·4·3·2, 4주차(진행 중) = 2
        insertEvent(userId, "w1", "2026-11-04 01:00:00", "2026-11-04 02:00:00");
        insertEvent(userId, "w2", "2026-11-11 01:00:00", "2026-11-11 05:00:00");
        insertEvent(userId, "w3", "2026-11-18 01:00:00", "2026-11-18 04:00:00");
        insertEvent(userId, "w4", "2026-11-25 01:00:00", "2026-11-25 03:00:00");

        ProgramWeekLoadRange range = query.range(userId, ONBOARDING, 4).orElseThrow();

        // 정렬 1·2·3·4 → 하위 5% = 1.15, 상위 5% = 3.85, 위치 = (2 − 1.15) ÷ 2.7 × 100
        assertThat(range.sampleCount()).isEqualTo(4);
        assertThat(range.low()).isCloseTo(1.15, within(1e-9));
        assertThat(range.high()).isCloseTo(3.85, within(1e-9));
        assertThat(range.position()).isCloseTo(31.4815, within(1e-3));
    }

    @Test
    void 일정이_없던_지난_주차는_표본에서_빼고_4개_미만이면_위치를_주지_않는다() {
        insertToken(userId, "google-calendar", "p-7", CALENDAR_SCOPES);
        // 1주차는 일정 없음
        insertEvent(userId, "x2", "2026-11-11 01:00:00", "2026-11-11 05:00:00");
        insertEvent(userId, "x3", "2026-11-18 01:00:00", "2026-11-18 04:00:00");
        insertEvent(userId, "x4", "2026-11-25 01:00:00", "2026-11-25 03:00:00");

        ProgramWeekLoadRange range = query.range(userId, ONBOARDING, 4).orElseThrow();

        assertThat(range.sampleCount()).isEqualTo(3);
        assertThat(range.position()).isNull();
        assertThat(range.low()).isNull();
        assertThat(range.high()).isNull();
    }

    @Test
    void 진행_중인_주차는_일정이_없어도_현재_값으로_센다() {
        insertToken(userId, "google-calendar", "p-8", CALENDAR_SCOPES);
        insertEvent(userId, "y1", "2026-11-04 01:00:00", "2026-11-04 02:00:00");
        insertEvent(userId, "y2", "2026-11-11 01:00:00", "2026-11-11 05:00:00");
        insertEvent(userId, "y3", "2026-11-18 01:00:00", "2026-11-18 04:00:00");

        ProgramWeekLoadRange range = query.range(userId, ONBOARDING, 4).orElseThrow();

        // 0·1·3·4 → 4주차 부하 0이 가장 낮다
        assertThat(range.sampleCount()).isEqualTo(4);
        assertThat(range.position()).isZero();
    }

    @Test
    void 캘린더가_연결되지_않았으면_내_범위_위치도_empty() {
        insertEvent(userId, "z1", "2026-11-04 01:00:00", "2026-11-04 02:00:00");

        assertThat(query.range(userId, ONBOARDING, 4)).isEmpty();
    }

    private long insertUser(String sub) {
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, created_at, last_login_at) "
                + "VALUES (?, 'PROFILE', '2026-11-11 22:30:00.000000', '2026-11-11 22:30:00.000000')", sub);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertToken(long user, String provider, String principal, String scopes) {
        jdbc.update("INSERT INTO oauth_tokens (user_id, provider, principal_name, access_token_enc, scopes, "
                + "updated_at) VALUES (?, ?, ?, 'enc', ?, '2026-11-11 22:30:00.000000')",
                user, provider, principal, scopes);
    }

    private void insertEvent(long user, String googleId, String startUtc, String endUtc) {
        jdbc.update("INSERT INTO calendar_events (user_id, google_event_id, start_at, end_at, all_day, "
                + "event_status, transparency, created_by_app, category, synced_at) "
                + "VALUES (?, ?, ?, ?, FALSE, 'confirmed', 'opaque', FALSE, 'CLASS', '2026-11-11 22:30:00.000000')",
                user, googleId, startUtc, endUtc);
    }
}
