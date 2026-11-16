package com.comedown.recommendation.contract;

import java.util.List;

/**
 * 제안 스냅샷을 API Suggestion 객체로 바꾸는 계약. PRE_START의 relatedEvent(대비할 마감 일정)를 붙인다.
 * 일정 제목을 읽으려고 구글을 부를 수 있으므로 DB 트랜잭션 밖에서 호출한다.
 */
public interface SuggestionPayloads {

    /** 순서를 유지한다. 일정 참조가 있는 제안이 없으면 구글을 부르지 않는다. */
    List<SuggestionPayload> of(long userId, List<SuggestionView> views);

    default SuggestionPayload of(long userId, SuggestionView view) {
        return of(userId, List.of(view)).get(0);
    }
}
