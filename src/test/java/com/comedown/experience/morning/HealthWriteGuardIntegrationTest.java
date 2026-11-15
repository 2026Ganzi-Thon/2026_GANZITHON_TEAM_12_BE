package com.comedown.experience.morning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.AccountCommands;
import com.comedown.account.contract.AccountDeletion;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.experience.assessments.application.CbiSubmitFlow;
import com.comedown.experience.morning.application.ConditionSaveFlow;
import com.comedown.experience.morning.application.MeasurementSaveFlow;
import com.comedown.experience.morning.application.ValidatedMeasurement;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 건강 기록 저장(컨디션·측정·CBI)과 동의 철회·탈퇴의 경계. Controller의 접근 확인을 통과한 뒤 저장 트랜잭션이 시작되기 전에
 * 철회·탈퇴가 끝난 상황을, 저장 흐름을 직접 호출해 재현한다. 기준 시각 2026-11-12 07:30 서울.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class HealthWriteGuardIntegrationTest {

    private static final LocalDate ONBOARDING_DATE = LocalDate.of(2026, 11, 10);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ConditionSaveFlow conditionFlow;

    @Autowired
    MeasurementSaveFlow measurementFlow;

    @Autowired
    CbiSubmitFlow cbiFlow;

    @Autowired
    AccountAccessQuery accountAccess;

    @Autowired
    AccountDeletion userLock;

    @Autowired
    AccountCommands account;

    @Autowired
    PlatformTransactionManager transactionManager;

    private long userId;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        String sub = "write-guard-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', '2026-11-10 01:00:00', '2026-11-10 00:00:00', "
                + "'2026-11-10 00:00:00')", sub);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        jdbc.update("INSERT INTO user_settings (user_id, hrv_enabled, updated_at) VALUES (?, TRUE, "
                + "'2026-11-10 00:00:00')", userId);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', '2026-11-01', '2026-11-10 00:50:00')", userId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'write-guard-%'");
    }

    @Test
    void 동의가_유효하면_저장_흐름은_그대로_저장한다() {
        conditionFlow.save(userId, ONBOARDING_DATE, 3, false, false, false);

        assertThat(count("morning_checks")).isEqualTo(1);
    }

    @Test
    void 확인_뒤에_동의가_철회됐으면_컨디션을_저장하지_않는다() {
        withdrawConsent();

        assertRejected(() -> conditionFlow.save(userId, ONBOARDING_DATE, 3, false, false, false),
                ErrorCode.HRV_CONSENT_REQUIRED);
        assertThat(count("morning_checks")).isZero();
        assertThat(count("hrv_daily_states")).isZero();
    }

    @Test
    void 확인_뒤에_동의가_철회됐으면_측정을_저장하지_않고_재시도_키도_남기지_않는다() {
        withdrawConsent();

        assertRejected(() -> measurementFlow.save(userId, ONBOARDING_DATE, UUID.randomUUID(), Map.of("rmssdMs", 42.0),
                new ValidatedMeasurement.Manual(Instant.parse("2026-11-11T22:28:50Z"), 42.0)),
                ErrorCode.HRV_CONSENT_REQUIRED);
        assertThat(count("hrv_measurements")).isZero();
        assertThat(count("morning_checks")).isZero();
        assertThat(count("api_idempotency_keys")).isZero();
    }

    @Test
    void 확인_뒤에_동의가_철회됐으면_CBI를_저장하지_않는다() {
        HealthDataAccess checkedBefore = accountAccess.findHealthDataAccess(userId).orElseThrow();
        withdrawConsent();

        assertRejected(() -> cbiFlow.submit(checkedBefore, UUID.randomUUID(), Map.of("round", "DAY28"), "DAY28",
                Map.of(1, 1)), ErrorCode.HRV_CONSENT_REQUIRED);
        assertThat(count("cbi_assessments")).isZero();
        assertThat(count("api_idempotency_keys")).isZero();
    }

    @Test
    void 확인_뒤에_탈퇴가_시작됐으면_컨디션을_저장하지_않는다() {
        jdbc.update("UPDATE users SET deletion_status = 'DELETING', deletion_requested_at = '2026-11-11 22:00:00' "
                + "WHERE id = ?", userId);

        assertRejected(() -> conditionFlow.save(userId, ONBOARDING_DATE, 3, false, false, false),
                ErrorCode.INVALID_STATE);
        assertThat(count("morning_checks")).isZero();
    }

    @Test
    void 동의_철회가_진행_중이면_컨디션_저장은_끝나기를_기다렸다가_거절된다() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 동의 철회 트랜잭션: 사용자 행을 잠근 채 멈춰 있다가 철회하고 커밋한다.
            Future<?> withdrawal = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        userLock.lock(userId);
                        locked.countDown();
                        awaitQuietly(release);
                        account.withdrawHealthConsent(userId);
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> save = pool.submit(() -> conditionFlow.save(userId, ONBOARDING_DATE, 3, false, false, false));

            // 철회가 끝나기 전에는 저장이 진행되지 않는다(사용자 행 잠금 대기).
            assertThatThrownBy(() -> save.get(700, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(count("morning_checks")).isZero();

            release.countDown();
            withdrawal.get(10, TimeUnit.SECONDS);

            assertThatThrownBy(() -> save.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.HRV_CONSENT_REQUIRED));
            assertThat(count("morning_checks")).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ---------- 도우미 ----------

    private void withdrawConsent() {
        jdbc.update("UPDATE user_consents SET withdrawn_at = '2026-11-11 22:00:00' WHERE user_id = ?", userId);
    }

    private static void assertRejected(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
