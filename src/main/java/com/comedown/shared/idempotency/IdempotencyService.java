package com.comedown.shared.idempotency;

import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.idempotency.IdempotencyBegin.InProgress;
import com.comedown.shared.idempotency.IdempotencyBegin.Replay;
import com.comedown.shared.idempotency.IdempotencyBegin.Started;
import com.comedown.shared.time.ServiceTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Idempotency-Key} 재시도 처리(API 명세 0절). 사용자 + 작업 + 키를 고유하게 저장하고 요청 본문 해시와 결과를 비교한다.
 * <ul>
 *   <li>처음 받은 키: 작업을 실행하고 응답(상태 코드·본문)을 저장한다.</li>
 *   <li>같은 키·같은 본문: 작업을 다시 실행하지 않고 저장된 응답을 돌려준다.</li>
 *   <li>같은 키·다른 본문: 409 {@code IDEMPOTENCY_CONFLICT}.</li>
 *   <li>같은 요청이 처리 중: 409 {@code INVALID_STATE}(일반 작업 기준).</li>
 *   <li>작업이 예외로 끝나면 키를 지워 같은 키로 다시 시도할 수 있게 한다. 오류 응답은 저장하지 않는다.</li>
 * </ul>
 * 보관은 최소 24시간이다. 만료된 완료 키는 재사용할 수 있고 {@link IdempotencyCleanupJob}이 정리한다.
 * 처리 중(PROCESSING) 키는 만료돼도 지우지 않는다(외부 승인 작업은 결과 확인 전 제거하지 않음).
 * <p>
 * 일반 작업은 {@link #execute}를 쓴다. 외부 호출을 트랜잭션 밖에서 해야 하는 흐름(추천 승인 등)은
 * {@link #begin} / {@link #complete} / {@link #abandon}을 직접 조합한다.
 * <p>
 * 응답 본문에는 건강정보가 들어갈 수 있으므로 건강 기록 삭제·탈퇴 때 {@link #deleteForUser}로 함께 지운다.
 */
@Service
public class IdempotencyService {

    static final int MAX_OPERATION_LENGTH = 80;
    static final long MIN_RETENTION_HOURS = 24;
    static final String IN_PROGRESS_MESSAGE = "같은 요청을 처리하고 있어요. 잠시 후 다시 시도해 주세요.";

    private final IdempotencyRepository repository;
    private final RequestHasher hasher;
    private final JsonMapper jsonMapper;
    private final ServiceTime serviceTime;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate required;
    private final Duration retention;

    public IdempotencyService(
            IdempotencyRepository repository,
            JsonMapper jsonMapper,
            ServiceTime serviceTime,
            PlatformTransactionManager transactionManager,
            @Value("${comedown.idempotency.retention-hours:24}") long retentionHours) {
        if (retentionHours < MIN_RETENTION_HOURS) {
            throw new IllegalArgumentException("재시도 키는 최소 24시간 보관해야 합니다: " + retentionHours);
        }
        this.repository = repository;
        this.hasher = new RequestHasher(jsonMapper);
        this.jsonMapper = jsonMapper;
        this.serviceTime = serviceTime;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.required = new TransactionTemplate(transactionManager);
        this.retention = Duration.ofHours(retentionHours);
    }

    /**
     * 재시도 키로 보호해 작업을 실행한다. 작업과 응답 저장은 한 트랜잭션이다.
     * 트랜잭션 밖(Controller·experience 흐름)에서 호출한다. 외부 API 호출을 action 안에 넣지 않는다.
     *
     * @param operation    작업+리소스 식별자(최대 80자). 예: {@code hrv-measurement:create}, {@code suggestion:31:approve}
     * @param request      요청 본문(해시만 저장)
     * @param responseType 저장된 응답을 되살릴 타입
     */
    public <T> IdempotentResponse<T> execute(long userId, String operation, UUID key, Object request,
            Class<T> responseType, Supplier<IdempotentResponse<T>> action) {
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(action, "action");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("재시도 키 작업은 트랜잭션 밖에서 시작해야 합니다");
        }
        return switch (begin(userId, operation, key, request)) {
            case Replay replay -> new IdempotentResponse<>(
                    replay.status(), readBody(replay.bodyJson(), responseType), true);
            case InProgress inProgress -> throw new ApiException(ErrorCode.INVALID_STATE, IN_PROGRESS_MESSAGE);
            case Started started -> run(started.recordId(), action);
        };
    }

    private <T> IdempotentResponse<T> run(long recordId, Supplier<IdempotentResponse<T>> action) {
        try {
            return required.execute(status -> {
                IdempotentResponse<T> response = Objects.requireNonNull(action.get(), "action 결과");
                complete(recordId, response.status(), response.body());
                return response;
            });
        } catch (RuntimeException | Error e) {
            try {
                abandon(recordId);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /**
     * 키를 등록하거나 기존 키의 상태를 돌려준다. PROCESSING 행 생성은 별도 트랜잭션으로 바로 커밋해
     * 동시에 들어온 같은 키 요청이 서로를 볼 수 있게 한다.
     *
     * @throws ApiException 같은 키·다른 본문이면 409 IDEMPOTENCY_CONFLICT
     */
    public IdempotencyBegin begin(long userId, String operation, UUID key, Object request) {
        validate(userId, operation, key);
        String requestKey = key.toString();
        String requestHash = hasher.hash(request);
        for (int attempt = 0; attempt < 3; attempt++) {
            Instant now = serviceTime.now();
            try {
                long id = requiresNew.execute(status -> repository.insertProcessing(
                        userId, operation, requestKey, requestHash, now, now.plus(retention)));
                return new Started(id);
            } catch (DuplicateKeyException duplicate) {
                Optional<IdempotencyRepository.Row> found =
                        requiresNew.execute(status -> repository.find(userId, operation, requestKey));
                if (found.isEmpty()) {
                    continue; // 그 사이 지워졌다(작업 실패 등). 다시 등록한다.
                }
                IdempotencyRepository.Row row = found.get();
                boolean completed = IdempotencyRepository.COMPLETED.equals(row.status());
                if (completed && !row.expiresAt().isAfter(now)) {
                    requiresNew.executeWithoutResult(status -> repository.deleteExpiredCompleted(row.id(), now));
                    continue; // 만료된 키는 새 요청으로 본다.
                }
                if (!row.requestHash().equals(requestHash)) {
                    throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
                }
                return completed ? new Replay(row.responseStatus(), row.responseBody()) : new InProgress(row.id());
            }
        }
        throw new ApiException(ErrorCode.INVALID_STATE, IN_PROGRESS_MESSAGE);
    }

    /** 작업 결과를 저장한다. 작업의 쓰기와 같은 트랜잭션에서 호출하는 것을 권장한다. */
    public void complete(long recordId, int responseStatus, Object body) {
        String json = jsonMapper.writeValueAsString(body);
        if (repository.complete(recordId, responseStatus, json) != 1) {
            throw new IllegalStateException("처리 중인 재시도 키가 아닙니다: " + recordId);
        }
    }

    /** 실패한 작업의 키를 지워 같은 키로 다시 시도할 수 있게 한다. 별도 트랜잭션으로 바로 반영한다. */
    public void abandon(long recordId) {
        requiresNew.executeWithoutResult(status -> repository.deleteProcessing(recordId));
    }

    /** 저장된 응답 본문을 되살린다. */
    public <T> T readBody(String bodyJson, Class<T> type) {
        return bodyJson == null ? null : jsonMapper.readValue(bodyJson, type);
    }

    /** 사용자의 모든 재시도 키를 지운다(탈퇴). */
    public int deleteForUser(long userId) {
        return required.execute(status -> repository.deleteByUser(userId));
    }

    /** 사용자의 특정 작업 키를 지운다(예: 건강 기록 삭제 때 {@code hrv-} 작업). */
    public int deleteForUser(long userId, String operationPrefix) {
        if (operationPrefix == null || operationPrefix.isBlank()) {
            throw new IllegalArgumentException("operationPrefix가 필요합니다");
        }
        return required.execute(status -> repository.deleteByUserAndOperationPrefix(userId, operationPrefix));
    }

    /** 만료된 완료 키를 정리한다. 처리 중인 키는 남긴다. */
    public int purgeExpired() {
        Instant now = serviceTime.now();
        return required.execute(status -> repository.deleteAllExpiredCompleted(now));
    }

    private static void validate(long userId, String operation, UUID key) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId가 올바르지 않습니다: " + userId);
        }
        if (operation == null || operation.isBlank() || operation.length() > MAX_OPERATION_LENGTH) {
            throw new IllegalArgumentException("operation은 1~80자여야 합니다");
        }
        Objects.requireNonNull(key, "key");
    }
}
