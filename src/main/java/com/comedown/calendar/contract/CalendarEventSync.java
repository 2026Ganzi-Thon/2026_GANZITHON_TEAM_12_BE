package com.comedown.calendar.contract;

import com.comedown.shared.time.InstantRange;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 동기화한 일정을 저장하는 계약. calendar_events 쓰기는 calendar만 한다. */
public interface CalendarEventSync {

    /**
     * 범위 안의 저장된 일정을 {@code events}와 맞춘다: 새 일정 추가, 바뀐 일정 갱신, 사라진 일정 삭제.
     * 사용자가 직접 고친 분류(USER)는 덮어쓰지 않고 분류 수정 규칙을 새 일정에도 적용한다.
     *
     * @param referencedEventIds 다른 기능(제안)이 참조하는 일정 id. 사라졌어도 지우지 않고 취소 상태로 둔다
     */
    default SyncResult apply(long userId, InstantRange range, List<SyncedEvent> events,
            Set<Long> referencedEventIds) {
        return apply(userId, range, events, referencedEventIds, Map.of());
    }

    /**
     * @param aiCategories {@link CalendarAiClassifier}가 낸 google event id → EventCategory 이름.
     *                     사용자가 직접 고친 분류와 분류 수정 규칙보다는 낮고 제목 규칙보다는 높다(USER &gt; AI &gt; RULE)
     */
    SyncResult apply(long userId, InstantRange range, List<SyncedEvent> events, Set<Long> referencedEventIds,
            Map<String, String> aiCategories);
}
