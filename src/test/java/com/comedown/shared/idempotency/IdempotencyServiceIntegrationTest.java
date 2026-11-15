package com.comedown.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재시도 키 처리를 MySQL 8.4 위에서 검증한다. 키 등록이 별도 트랜잭션으로 커밋되므로 테스트 트랜잭션 롤백을 쓰지 않고
 * 사용자 행을 지워(CASCADE) 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class IdempotencyServiceIntegrationTest {

    private static final String OPERATION = "hrv-measurement:create";
    private static final String NOW = "'2026-11-11 22:30:00.000000'";

    @Autowired
    IdempotencyService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    TransactionTemplate transactionTemplate;

    record SampleRequest(String source, double value) {
    }

    record SampleResponse(long id, String quality) {
    }

    private long userId;
    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        userId = insertUser("idem-" + UUID.randomUUID());
        runs.set(0);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'idem-%'");
    }

    private IdempotentResponse<SampleResponse> call(long user, String operation, UUID key, SampleRequest request) {
        return service.execute(user, operation, key, request, SampleResponse.class, () -> {
            runs.incrementAndGet();
            return IdempotentResponse.of(201, new SampleResponse(runs.get(), "GOOD"));
        });
    }

    @Test
    void 처음_받은_키는_작업을_실행하고_응답을_저장한다() {
        UUID key = UUID.randomUUID();

        IdempotentResponse<SampleResponse> response = call(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0));

        assertThat(runs).hasValue(1);
        assertThat(response.replayed()).isFalse();
        assertThat(response.status()).isEqualTo(201);
        assertThat(jdbc.queryForMap("SELECT status, response_status, request_key FROM api_idempotency_keys "
                + "WHERE user_id = ?", userId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("response_status", 201)
                .containsEntry("request_key", key.toString());
    }

    @Test
    void 같은_키_같은_본문은_작업을_다시_하지_않고_저장된_응답을_돌려준다() {
        UUID key = UUID.randomUUID();
        IdempotentResponse<SampleResponse> first = call(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0));

        IdempotentResponse<SampleResponse> second = call(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0));

        assertThat(runs).hasValue(1);
        assertThat(second.replayed()).isTrue();
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
    }

    @Test
    void 같은_키_다른_본문은_409_IDEMPOTENCY_CONFLICT() {
        UUID key = UUID.randomUUID();
        call(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0));

        assertThatThrownBy(() -> call(userId, OPERATION, key, new SampleRequest("CAMERA", 2.0)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        assertThat(runs).hasValue(1);
    }

    @Test
    void 같은_키라도_사용자나_작업이_다르면_별개다() {
        UUID key = UUID.randomUUID();
        long otherUser = insertUser("idem-other-" + UUID.randomUUID());

        call(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0));
        call(otherUser, OPERATION, key, new SampleRequest("CAMERA", 2.0));
        call(userId, "cbi-assessment:create", key, new SampleRequest("CAMERA", 3.0));

        assertThat(runs).hasValue(3);
    }

    @Test
    void 작업이_실패하면_쓰기를_되돌리고_키를_지워_같은_키로_다시_시도할_수_있다() {
        UUID key = UUID.randomUUID();

        assertThatThrownBy(() -> service.execute(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0),
                SampleResponse.class, () -> {
                    insertMorningCheck(userId, "2026-11-12");
                    throw new ApiException(ErrorCode.HRV_CONSENT_REQUIRED);
                }))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.HRV_CONSENT_REQUIRED));

        assertThat(count("SELECT COUNT(*) FROM morning_checks WHERE user_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM api_idempotency_keys WHERE user_id = ?")).isZero();

        IdempotentResponse<SampleResponse> retry = call(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0));
        assertThat(retry.replayed()).isFalse();
        assertThat(runs).hasValue(1);
    }

    @Test
    void 같은_요청이_처리_중이면_409_INVALID_STATE() {
        UUID key = UUID.randomUUID();
        IdempotencyBegin first = service.begin(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0));
        assertThat(first).isInstanceOf(IdempotencyBegin.Started.class);

        assertThat(service.begin(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0)))
                .isEqualTo(new IdempotencyBegin.InProgress(((IdempotencyBegin.Started) first).recordId()));
        assertThatThrownBy(() -> call(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        assertThat(runs).hasValue(0);
    }

    @Test
    void 완료_키는_24시간_동안_재생되고_만료되면_새_요청으로_처리된다() {
        UUID key = UUID.randomUUID();
        call(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0));

        clock.advance(Duration.ofHours(24).minusSeconds(1));
        assertThat(call(userId, OPERATION, key, new SampleRequest("CAMERA", 1.0)).replayed()).isTrue();

        clock.advance(Duration.ofSeconds(1));
        IdempotentResponse<SampleResponse> afterExpiry =
                call(userId, OPERATION, key, new SampleRequest("CAMERA", 2.0));
        assertThat(afterExpiry.replayed()).isFalse();
        assertThat(runs).hasValue(2);
    }

    @Test
    void 정리는_만료된_완료_키만_지우고_처리_중인_키는_남긴다() {
        call(userId, OPERATION, UUID.randomUUID(), new SampleRequest("CAMERA", 1.0));
        service.begin(userId, "suggestion:31:approve", UUID.randomUUID(), new SampleRequest("APPROVE", 0));
        clock.advance(Duration.ofHours(25));
        call(userId, OPERATION, UUID.randomUUID(), new SampleRequest("CAMERA", 2.0));

        int deleted = service.purgeExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT operation FROM api_idempotency_keys WHERE user_id = ? ORDER BY id",
                String.class, userId))
                .containsExactly("suggestion:31:approve", OPERATION);
    }

    @Test
    void 건강_기록_삭제용으로_작업_접두어별_키를_지울_수_있다() {
        call(userId, OPERATION, UUID.randomUUID(), new SampleRequest("CAMERA", 1.0));
        call(userId, "hrv_x:create", UUID.randomUUID(), new SampleRequest("CAMERA", 1.0));
        call(userId, "suggestion:31:approve", UUID.randomUUID(), new SampleRequest("APPROVE", 0));

        assertThat(service.deleteForUser(userId, "hrv-")).isEqualTo(1);
        // LIKE 와일드카드(_)를 문자 그대로 비교한다
        assertThat(service.deleteForUser(userId, "hrv_")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM api_idempotency_keys WHERE user_id = ?")).isEqualTo(1);
        assertThat(service.deleteForUser(userId)).isEqualTo(1);
    }

    @Test
    void 트랜잭션_안에서_execute를_시작하면_거부한다() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                call(userId, OPERATION, UUID.randomUUID(), new SampleRequest("CAMERA", 1.0))))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- 도우미 ----------

    private long insertUser(String googleSub) {
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, created_at, last_login_at) "
                + "VALUES (?, 'DONE', " + NOW + ", " + NOW + ")", googleSub);
        return jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, googleSub);
    }

    private void insertMorningCheck(long user, String date) {
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, created_at) "
                + "VALUES (?, ?, 1, " + NOW + ")", user, date);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, userId);
    }
}
