package com.comedown.calendar.contract;

import java.util.List;
import java.util.Map;

/**
 * 제목 규칙으로 분류하지 못한 일정을 AI로 분류하는 계약. 외부 호출이 있으므로 동기화 저장 트랜잭션 전에 부른다.
 * 결과는 {@link CalendarEventSync#apply}에 넘겨 저장한다.
 */
public interface CalendarAiClassifier {

    /**
     * 규칙으로는 {@code OTHER}인 일정 중 아직 AI가 분류하지 않았거나 제목이 바뀐 것만 AI에 묻는다.
     * AI에는 일정 제목만 보낸다(시간·참석자·설명은 보내지 않음).
     *
     * @return google event id → EventCategory 이름. AI를 쓸 수 없거나 실패했으면 비어 있다(규칙 분류를 그대로 쓴다)
     */
    Map<String, String> classify(long userId, List<SyncedEvent> events);
}
