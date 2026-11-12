package com.comedown.recommendation.contract;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 제안 스냅샷(API Suggestion 객체의 원천). 시각은 UTC Instant이며 응답 변환은 호출하는 쪽에서 한다.
 *
 * @param registrationKey 서버가 만든 Google event id(승인 재시도에 그대로 사용). 외부에 노출하지 않는다
 * @param relatedEventId  PRE_START가 대비하는 calendar_events.id(일정 제목·분류는 calendar가 붙인다)
 */
public record SuggestionView(
        long id,
        long userId,
        String type,
        String status,
        LocalDate targetDate,
        Instant start,
        Instant end,
        String title,
        String activityCode,
        String reasonCode,
        String judgmentState,
        String message,
        boolean aiGenerated,
        boolean fallback,
        Long relatedEventId,
        LocalDate riskWeekStart,
        String googleEventId,
        String registrationKey,
        Instant doneAt) {

    public long durationMin() {
        return java.time.Duration.between(start, end).toMinutes();
    }
}
