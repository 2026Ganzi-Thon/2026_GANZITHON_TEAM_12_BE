package com.comedown.ai.application;

import com.comedown.ai.contract.AiCompletionPort;
import com.comedown.ai.contract.AiDataDeletion;
import com.comedown.ai.contract.AiInterpretation;
import com.comedown.ai.infrastructure.persistence.AiOutputStore;
import com.comedown.shared.time.ServiceTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 해석 문구 생성과 캐시. 순서: 저장된 문구 확인 → (없으면) AI 호출 → 검증 → 저장. AI를 쓸 수 없거나 실패하면 규칙 기반
 * 문구로 대체한다. 외부 호출을 DB 트랜잭션에 넣지 않으려고 이 메서드는 트랜잭션을 열지 않고 읽기·쓰기를 각각 한다.
 * <p>
 * 실패해 대체 문구를 저장한 경우 {@link #RETRY_FALLBACK_AFTER}가 지나면 다시 시도한다(그 사이에는 저장된 대체 문구를
 * 돌려줘 화면이 매번 타임아웃을 기다리지 않게 한다). API 키가 없으면 호출도 저장도 하지 않는다.
 */
@Service
class AiInterpretationService implements AiInterpretation, AiDataDeletion {

    static final Duration RETRY_FALLBACK_AFTER = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(AiInterpretationService.class);

    private final AiCompletionPort port;
    private final AiOutputStore store;
    private final ServiceTime time;

    AiInterpretationService(AiCompletionPort port, AiOutputStore store, ServiceTime time) {
        this.port = port;
        this.store = store;
        this.time = time;
    }

    @Override
    public Text interpret(Request request) {
        if (!port.available()) {
            return new Text(request.fallbackText(), false, true);
        }
        String hash = InterpretationPrompt.inputHash(request.kind(), request.facts(), request.fallbackText());
        try {
            Optional<AiOutputStore.Row> cached = store.find(request.userId(), request.kind(), request.refDate(), hash);
            if (cached.isPresent() && !shouldRetry(cached.get())) {
                AiOutputStore.Row row = cached.get();
                return new Text(row.content(), !row.fallback(), row.fallback());
            }
            Optional<String> written = generate(request.kind(), request.facts(), request.fallbackText());
            store.save(request.userId(), request.kind(), request.refDate(), hash,
                    written.orElse(request.fallbackText()), written.isPresent() ? port.model() : null,
                    written.isEmpty(), time.now());
            return written.map(text -> new Text(text, true, false))
                    .orElseGet(() -> new Text(request.fallbackText(), false, true));
        } catch (DataAccessException e) {
            // 캐시를 못 읽거나 못 써도(예: 그 사이 탈퇴로 사용자 행이 없어짐) 화면에는 대체 문구를 준다.
            log.warn("AI 문구 캐시 접근 실패(kind={}): {}", request.kind(), e.getClass().getSimpleName());
            return new Text(request.fallbackText(), false, true);
        }
    }

    private boolean shouldRetry(AiOutputStore.Row row) {
        return row.fallback() && row.createdAt().plus(RETRY_FALLBACK_AFTER).isBefore(time.now());
    }

    /** AI가 쓴 문장. 호출이 실패했거나 검증을 통과하지 못하면 비어 있다. 문구 내용은 로그에 남기지 않는다. */
    private Optional<String> generate(String kind, List<String> facts, String fallbackText) {
        try {
            String raw = port.complete(InterpretationPrompt.system(kind),
                    InterpretationPrompt.user(kind, facts, fallbackText));
            Optional<String> cleaned = InterpretationPrompt.clean(raw);
            if (cleaned.isEmpty()) {
                log.warn("AI 문구가 검증을 통과하지 못해 대체 문구를 씁니다(kind={}).", kind);
            }
            return cleaned;
        } catch (RuntimeException e) {
            log.warn("AI 문구 생성 실패(kind={}): {}", kind, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> write(String kind, List<String> facts, String fallbackText) {
        if (!port.available()) {
            return Optional.empty();
        }
        return generate(kind, List.copyOf(facts), fallbackText);
    }

    @Override
    @Transactional
    public int deleteAll(long userId) {
        return store.deleteAll(userId);
    }
}
