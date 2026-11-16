package com.comedown.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.comedown.recommendation.contract.NewSuggestion;
import com.comedown.recommendation.contract.SuggestionApprovalCommands;
import com.comedown.recommendation.contract.SuggestionCommands;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * 제안 상태 전이의 직렬화(MySQL 잠금). 먼저 시작한 전이를 커밋 전에 멈춰 두고 다른 전이를 시작해, 나중 전이가 먼저 커밋된
 * 상태를 보고 판단하는지(오래된 상태로 덮어쓰지 않는지) 확인한다. 기준 시각 2026-11-12 07:30 서울,
 * 제안 시간 16:00~16:20 서울(07:00~07:20 UTC).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class SuggestionTransitionLockIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    SuggestionCommands commands;

    @Autowired
    SuggestionApprovalCommands approvals;

    @Autowired
    PlatformTransactionManager transactionManager;

    private long userId;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        String sub = "suggestion-lock-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', '2026-11-10 01:00:00', '2026-11-01 00:00:00', "
                + "'2026-11-01 00:00:00')", sub);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        clock.set(FixedClockTestConfig.DEFAULT_NOW); // 같은 컨텍스트를 쓰는 다른 테스트가 기본 시각을 전제한다
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'suggestion-lock-%'");
    }

    @Test
    void 승인이_커밋되기_전에_시작한_건너뛰기는_APPROVING을_덮어쓰지_못한다() throws Exception {
        SuggestionView s = propose();

        Future<SuggestionView> dismiss = startWhileHolding(
                () -> approvals.startApproval(userId, s.id(), null, null),
                () -> commands.dismiss(userId, s.id()));

        assertRejected(dismiss, ErrorCode.INVALID_STATE);
        assertThat(statusOf(s.id())).isEqualTo("APPROVING");
        // 승인 흐름은 구글 등록 뒤 그대로 확정할 수 있다(확정하지 못하는 구글 일정이 남지 않는다).
        assertThat(approvals.confirmApproved(userId, s.id(), s.registrationKey()).status()).isEqualTo("APPROVED");
    }

    @Test
    void 승인이_커밋되기_전에_시작한_만료는_APPROVING을_덮어쓰지_못한다() throws Exception {
        SuggestionView s = propose();
        clock.set("2026-11-13T00:00:00+09:00"); // 만료 시각(그날 끝) 이후, 만료 작업이 아직 돌기 전

        Future<Integer> expiry = startWhileHolding(
                () -> approvals.startApproval(userId, s.id(), null, null),
                () -> commands.expireDue());

        expiry.get(10, TimeUnit.SECONDS);
        assertThat(statusOf(s.id())).isEqualTo("APPROVING");
        assertThat(approvals.confirmApproved(userId, s.id(), s.registrationKey()).status()).isEqualTo("APPROVED");
    }

    @Test
    void 건너뛰기가_커밋되기_전에_시작한_승인은_기다렸다가_거절된다() throws Exception {
        SuggestionView s = propose();

        Future<SuggestionApprovalCommands.ApprovalStart> approval = startWhileHolding(
                () -> commands.dismiss(userId, s.id()),
                () -> approvals.startApproval(userId, s.id(), null, null));

        assertRejected(approval, ErrorCode.SUGGESTION_NOT_PROPOSED);
        assertThat(statusOf(s.id())).isEqualTo("DISMISSED");
    }

    @Test
    void 되돌리기_확정이_커밋되기_전에_시작한_했어요는_UNDONE을_덮어쓰지_못한다() throws Exception {
        SuggestionView s = propose();
        approvals.startApproval(userId, s.id(), null, null);
        approvals.confirmApproved(userId, s.id(), s.registrationKey());
        clock.set("2026-11-12T16:30:00+09:00"); // 일정이 끝난 뒤(했어요를 누를 수 있는 시각)

        Future<SuggestionView> done = startWhileHolding(
                () -> approvals.confirmUndone(userId, s.id()),
                () -> commands.markDone(userId, s.id()));

        assertRejected(done, ErrorCode.INVALID_STATE);
        assertThat(statusOf(s.id())).isEqualTo("UNDONE");
        assertThat(jdbc.queryForObject("SELECT done_at FROM suggestions WHERE id = ?", java.sql.Timestamp.class,
                s.id())).isNull();
    }

    // ---------- 도우미 ----------

    /**
     * {@code first}를 트랜잭션 안에서 실행한 뒤 커밋하지 않고 멈춰 두고, 그 동안 {@code second}를 시작한다.
     * {@code second}가 {@code first}의 커밋을 기다리는지 확인한 다음 {@code first}를 커밋시킨다.
     */
    private <T> Future<T> startWhileHolding(Runnable first, Callable<T> second) throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> held = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        first.run();
                        holding.countDown();
                        awaitQuietly(release);
                    }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

            Future<T> contender = pool.submit(second);
            // 먼저 시작한 전이가 커밋되기 전에는 끝나지 않는다(사용자 잠금 대기).
            assertThatThrownBy(() -> contender.get(700, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            held.get(10, TimeUnit.SECONDS);
            return contender;
        } finally {
            release.countDown();
        }
    }

    private static void assertRejected(Future<?> result, ErrorCode expected) {
        assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
    }

    /** 오늘 16:00~16:20 서울의 회복 추천. 만료 시각은 그날 끝. */
    private SuggestionView propose() {
        Instant start = Instant.parse("2026-11-12T07:00:00Z");
        return commands.propose(new NewSuggestion(userId, "RECOVERY", TODAY, start, start.plusSeconds(20 * 60L),
                "20분 걷기", "WALK", null, "LOW_RECOVERY", "recovery-v1", null, false, false, null, null,
                TODAY.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()));
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM suggestions WHERE id = ?", String.class, id);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
