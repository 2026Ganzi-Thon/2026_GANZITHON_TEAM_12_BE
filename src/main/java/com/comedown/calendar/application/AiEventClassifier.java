package com.comedown.calendar.application;

import com.comedown.ai.contract.AiCompletionPort;
import com.comedown.calendar.contract.CalendarAiClassifier;
import com.comedown.calendar.contract.SyncedEvent;
import com.comedown.calendar.domain.AiClassificationPrompt;
import com.comedown.calendar.domain.CategorySource;
import com.comedown.calendar.domain.Classification;
import com.comedown.calendar.domain.EventCategory;
import com.comedown.calendar.domain.RuleClassifier;
import com.comedown.calendar.infrastructure.persistence.CalendarEventStore;
import com.comedown.calendar.infrastructure.persistence.StoredEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 제목 규칙으로 분류하지 못한 일정의 AI 분류. 같은 제목은 한 번만 묻고, 이미 AI가 분류했고 제목이 그대로인 일정과 사용자가
 * 직접 고친 일정은 묻지 않는다. 트랜잭션을 열지 않는다(읽기는 각각, 외부 호출은 그 밖에서).
 * 제목은 로그에 남기지 않는다.
 */
@Service
class AiEventClassifier implements CalendarAiClassifier {

    static final int MAX_OUTPUT_TOKENS = 600;
    private static final Logger log = LoggerFactory.getLogger(AiEventClassifier.class);

    private final AiCompletionPort port;
    private final CalendarEventStore store;
    private final TitleHasher titleHasher;

    AiEventClassifier(AiCompletionPort port, CalendarEventStore store, TitleHasher titleHasher) {
        this.port = port;
        this.store = store;
        this.titleHasher = titleHasher;
    }

    @Override
    public Map<String, String> classify(long userId, List<SyncedEvent> events) {
        if (!port.available()) {
            return Map.of();
        }
        Map<String, List<String>> idsByTitle = candidates(userId, events);
        if (idsByTitle.isEmpty()) {
            return Map.of();
        }
        List<String> titles = new ArrayList<>(idsByTitle.keySet());
        if (titles.size() > AiClassificationPrompt.MAX_TITLES) {
            titles = titles.subList(0, AiClassificationPrompt.MAX_TITLES); // 나머지는 다음 동기화에서
        }
        Map<Integer, EventCategory> answered;
        try {
            answered = AiClassificationPrompt.parse(
                    port.complete(AiClassificationPrompt.SYSTEM, AiClassificationPrompt.user(titles),
                            MAX_OUTPUT_TOKENS),
                    titles.size());
        } catch (RuntimeException e) {
            log.warn("일정 AI 분류 실패({}건): {}", titles.size(), e.getClass().getSimpleName());
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<Integer, EventCategory> answer : answered.entrySet()) {
            for (String googleEventId : idsByTitle.get(titles.get(answer.getKey()))) {
                result.put(googleEventId, answer.getValue().name());
            }
        }
        return result;
    }

    /** AI에 물을 제목 → 그 제목의 일정 id들(제목 순서 유지). */
    private Map<String, List<String>> candidates(long userId, List<SyncedEvent> events) {
        List<SyncedEvent> unmatched = events.stream()
                .filter(e -> !"cancelled".equalsIgnoreCase(e.status()))
                .filter(e -> !e.createdByApp() && e.title() != null && !e.title().isBlank())
                .filter(e -> RuleClassifier.classify(e.title(), false).category() == EventCategory.OTHER)
                .toList();
        if (unmatched.isEmpty()) {
            return Map.of();
        }
        Map<String, StoredEvent> stored = new HashMap<>();
        store.findByGoogleIds(userId, unmatched.stream().map(SyncedEvent::googleEventId).toList())
                .forEach(e -> stored.put(e.googleEventId(), e));
        Map<String, List<String>> idsByTitle = new LinkedHashMap<>();
        for (SyncedEvent e : unmatched) {
            if (alreadyDecided(stored.get(e.googleEventId()), titleHasher.hash(userId, e.title()))) {
                continue;
            }
            idsByTitle.computeIfAbsent(e.title().strip(), t -> new ArrayList<>()).add(e.googleEventId());
        }
        return idsByTitle;
    }

    /** 사용자가 직접 고쳤거나, AI가 이미 분류했고 제목이 그대로면 다시 묻지 않는다. */
    private static boolean alreadyDecided(StoredEvent row, String titleHash) {
        if (row == null) {
            return false;
        }
        Classification c = row.classification();
        if (c == null) {
            return false;
        }
        return c.source() == CategorySource.USER
                || (c.source() == CategorySource.AI && Objects.equals(row.titleHash(), titleHash));
    }
}
