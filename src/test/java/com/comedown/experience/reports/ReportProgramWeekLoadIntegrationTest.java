package com.comedown.experience.reports;

import static com.comedown.support.TestLogin.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.recovery.contract.RecoveryCommands;
import com.comedown.schedule.contract.ProgramWeekLoad;
import com.comedown.schedule.contract.ProgramWeekLoadQuery;
import com.comedown.schedule.contract.ProgramWeekLoadRange;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 일정축의 {@link ProgramWeekLoadQuery}가 있을 때 리포트가 온보딩 기준 주차의 부하를 그대로 싣는지 본다(대체 구현 사용).
 * 기준 시각 2026-11-12 07:30 서울.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class,
        ReportProgramWeekLoadIntegrationTest.FakeWeekLoads.class})
class ReportProgramWeekLoadIntegrationTest {

    private static final double[] BASE = {3.60, 3.90, 3.70, 3.80, 3.65, 3.85, 3.75};

    /**
     * n주차 부하 = 10n, 평소 대비 = 5n%. 1주차는 직전 자료 부족. 캘린더 미연결 사용자는 empty.
     * 내 범위 위치는 진행 중인 주차가 4주차 이상이면 62.5(최저 11.5·최고 38.5), 그 전이면 표본 부족.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeWeekLoads {

        static final Set<Long> DISCONNECTED = ConcurrentHashMap.newKeySet();

        @Bean
        @Primary // 실제 schedule 구현이 있어도 이 테스트는 대체 구현으로 리포트 조합만 검증한다.
        ProgramWeekLoadQuery programWeekLoadQuery() {
            return new ProgramWeekLoadQuery() {
                @Override
                public Optional<List<ProgramWeekLoad>> find(long userId, LocalDate onboardingDate, int fromWeek,
                        int toWeek) {
                    if (DISCONNECTED.contains(userId)) {
                        return Optional.empty();
                    }
                    List<ProgramWeekLoad> weeks = new ArrayList<>();
                    for (int w = fromWeek; w <= toWeek; w++) {
                        LocalDate start = onboardingDate.plusDays(7L * (w - 1));
                        boolean first = w == 1;
                        weeks.add(new ProgramWeekLoad(w, start, start.plusDays(6), BigDecimal.valueOf(10.04 * w),
                                first ? null : BigDecimal.valueOf(5L * w).setScale(1),
                                first ? "INSUFFICIENT_DATA" : null));
                    }
                    return Optional.of(weeks);
                }

                @Override
                public Optional<ProgramWeekLoadRange> range(long userId, LocalDate onboardingDate, int currentWeek) {
                    if (DISCONNECTED.contains(userId)) {
                        return Optional.empty();
                    }
                    return Optional.of(currentWeek >= ProgramWeekLoadRange.MIN_SAMPLES
                            ? new ProgramWeekLoadRange(currentWeek, 62.5, 11.54, 38.46)
                            : new ProgramWeekLoadRange(currentWeek, null, null, null));
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    RecoveryCommands recoveryCommands;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        FakeWeekLoads.DISCONNECTED.clear();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'weekload-%'");
    }

    @Test
    void 회복_리포트는_주차별_부하와_진행_중인_주차의_평소_대비를_준다() throws Exception {
        long user = userWithBaseline();

        mvc.perform(get("/api/reports/recovery?days=14").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"loadVsUsualPct":10.0,
                         "graph":{"from":"2026-11-04","to":"2026-11-12","loadWeeks":[
                          {"week":1,"weekStart":"2026-11-04","weekEnd":"2026-11-10","current":false,"loadScore":10.0},
                          {"week":2,"weekStart":"2026-11-11","weekEnd":"2026-11-17","current":true,"loadScore":20.1}]}}
                        """));
    }

    @Test
    void 캘린더가_연결되지_않았으면_주차_칸만_주고_부하는_null() throws Exception {
        long user = userWithBaseline();
        FakeWeekLoads.DISCONNECTED.add(user);

        mvc.perform(get("/api/reports/recovery?days=14").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loadVsUsualPct").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.graph.loadWeeks.length()").value(2))
                .andExpect(jsonPath("$.graph.loadWeeks[0].loadScore").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.graph.loadWeeks[1].loadScore").value(Matchers.nullValue()));
    }

    @Test
    void 기준선_확정_전에도_잠긴_리포트에_일정_부하_평소_대비는_준다() throws Exception {
        // 서울 11-04 온보딩, 오늘 9일차(2주차)인데 측정 기록이 없어 기준선이 없다.
        long user = insertUser("2026-11-03 16:00:00");

        mvc.perform(get("/api/reports/recovery?days=14").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"locked":true,"unavailableReason":null,"unlock":{"requiredDays":7,"validDays":0},
                         "graph":null,"hrvVsUsualPct":null,"recoveryStatus":null,"latestQuality":null,
                         "loadVsUsualPct":10.0,"interpretation":null,"rangePosition":null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void 건강정보_동의가_없어도_일정_부하_평소_대비는_준다() throws Exception {
        long user = userWithBaseline();
        jdbc.update("UPDATE user_consents SET withdrawn_at = '2026-11-11 00:00:00' WHERE user_id = ?", user);

        mvc.perform(get("/api/reports/recovery?days=14").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.unavailableReason").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.graph").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.hrvVsUsualPct").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.loadVsUsualPct").value(10.0));
    }

    @Test
    void 잠긴_리포트도_캘린더가_연결되지_않았으면_일정_부하는_null() throws Exception {
        long user = insertUser("2026-11-03 16:00:00");
        FakeWeekLoads.DISCONNECTED.add(user);

        mvc.perform(get("/api/reports/recovery?days=14").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.loadVsUsualPct").value(Matchers.nullValue()));
    }

    @Test
    void 이십팔일차_리포트는_1주차와_4주차의_평소_대비를_준다() throws Exception {
        long user = insertUser("2026-10-16 01:00:00"); // 서울 10-16 → 오늘 28일차
        insertCbi(user, "INITIAL");
        insertCbi(user, "DAY28");

        mvc.perform(get("/api/reports/four-week").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(content().json("""
                        {"load":{"firstWeekVsUsualPct":null,"lastWeekVsUsualPct":20.0}}
                        """));

        FakeWeekLoads.DISCONNECTED.add(user);
        mvc.perform(get("/api/reports/four-week").with(loginAs(user)))
                .andExpect(jsonPath("$.load").value(Matchers.nullValue()));
    }

    @Test
    void 이십팔일차_이후_내_범위_위치에_일정_부하도_싣는다() throws Exception {
        long user = userWith30Trends();

        mvc.perform(get("/api/reports/recovery").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rangePosition.available").value(true))
                .andExpect(content().json("""
                        {"rangePosition":{"load":{"position":63,"low":11.5,"high":38.5},
                                          "loadUnavailableReason":null}}
                        """));
    }

    @Test
    void 캘린더가_연결되지_않았으면_내_범위_위치의_일정_부하는_null과_INSUFFICIENT_DATA() throws Exception {
        long user = userWith30Trends();
        FakeWeekLoads.DISCONNECTED.add(user);

        mvc.perform(get("/api/reports/recovery").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rangePosition.available").value(true))
                .andExpect(jsonPath("$.rangePosition.recovery").value(Matchers.notNullValue()))
                .andExpect(jsonPath("$.rangePosition.load").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.rangePosition.loadUnavailableReason").value("INSUFFICIENT_DATA"));
    }

    // ---------- 도우미 ----------

    /** 서울 10-01 온보딩(오늘 43일차 = 7주차), 10-13부터 30일 기록 → 7일 추세 값 20개 이상. */
    private long userWith30Trends() {
        long user = insertUser("2026-10-01 01:00:00");
        LocalDate first = LocalDate.of(2026, 10, 13);
        for (int i = 0; i < 30; i++) {
            seedDay(user, first.plusDays(i), 3.7 + 0.1 * Math.sin(i));
        }
        recoveryCommands.recalculate(user, first, LocalDate.of(2026, 11, 11));
        return user;
    }

    /** 서울 11-04 온보딩(오늘 9일차 = 2주차), 11-04~11-11 기록으로 기준선 확정. */
    private long userWithBaseline() {
        long user = insertUser("2026-11-03 16:00:00");
        LocalDate first = LocalDate.of(2026, 11, 4);
        for (int i = 0; i < 8; i++) {
            seedDay(user, first.plusDays(i), BASE[i % BASE.length]);
        }
        recoveryCommands.recalculate(user, first, LocalDate.of(2026, 11, 11));
        return user;
    }

    private long insertUser(String onboardingCompletedUtc) {
        String sub = "weekload-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', ?, '2026-09-01 00:00:00', '2026-09-01 00:00:00')",
                sub, onboardingCompletedUtc);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', '2026-09-01', '2026-09-01 00:00:00')", id);
        return id;
    }

    private void insertCbi(long user, String round) {
        jdbc.update("INSERT INTO cbi_assessments (user_id, round, questionnaire_version, personal_score, "
                + "work_score, high_risk, taken_at) VALUES (?, ?, 'cbi-ko-2026.11.2', 50, 50, FALSE, "
                + "'2026-11-01 00:00:00')", user, round);
    }

    /** 컨디션까지 끝난 날(GOOD 측정 1건). */
    private void seedDay(long user, LocalDate date, double ln) {
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, condition_score, alcohol, sick, "
                + "caffeine, completed_at, created_at) VALUES (?, ?, 1, 3, FALSE, FALSE, FALSE, ?, ?)",
                user, date, date + " 00:30:00", date + " 00:00:00");
        long checkId = jdbc.queryForObject("SELECT id FROM morning_checks WHERE user_id = ? AND check_date = ?",
                Long.class, user, date);
        jdbc.update("INSERT INTO hrv_measurements (user_id, morning_check_id, measured_at, source, rmssd_ms, "
                + "ln_rmssd, mean_hr_bpm, quality_level, algorithm_version, created_at) VALUES (?, ?, ?, 'CAMERA', "
                + "?, ?, 60.0, 'GOOD', 'hrv-cam-v1', '2026-11-12 00:00:00')",
                user, checkId, date.minusDays(1) + " 22:00:00", Math.exp(ln), ln);
    }
}
