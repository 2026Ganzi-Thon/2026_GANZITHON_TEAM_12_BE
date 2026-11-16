package com.comedown.calendar.contract;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** 일정 id로 카드에 보여 줄 일정 요약을 읽는 계약(제안의 relatedEvent 등). */
public interface CalendarEventSummaryQuery {

    /**
     * @param title    구글에서 실시간으로 읽은 제목. 읽지 못했으면 null(제목은 저장하지 않는다)
     * @param start    일정 시작(UTC). 종일 일정이면 서울 그 날 00:00
     * @param category EventCategory 이름. 분류 전이면 null
     */
    record EventSummary(long id, String title, Instant start, String category) {

        @Override
        public String toString() {
            return "EventSummary[" + id + "]";
        }
    }

    /**
     * 사용자 본인의 일정만 id별로 돌려준다(없는 id·남의 일정은 빠진다). 제목을 읽으려고 구글을 부르므로
     * DB 트랜잭션 밖에서 호출한다. 구글을 읽지 못해도 저장된 값(시작·분류)은 돌려준다.
     */
    Map<Long, EventSummary> findByIds(long userId, Collection<Long> eventIds);
}
