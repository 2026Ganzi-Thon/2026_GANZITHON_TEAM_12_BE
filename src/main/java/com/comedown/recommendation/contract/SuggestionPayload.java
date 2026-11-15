package com.comedown.recommendation.contract;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * API 공통 객체 Suggestion(API 명세 0절 "자주 쓰는 객체"). 제안 목록·홈·아침 결과·승인 응답이 같은 모양을 쓴다.
 * 등록 키는 내보내지 않는다.
 *
 * @param relatedEvent PRE_START가 대비하는 마감 일정 {id, title, start, category}. calendar 읽기 계약이 생기기 전까지 null
 * @param message      카드 문구 {text, aiGenerated, fallback}. 문구가 없으면 null
 */
public record SuggestionPayload(
        long id,
        String type,
        String status,
        LocalDate targetDate,
        OffsetDateTime start,
        OffsetDateTime end,
        long durationMin,
        String title,
        String activityCode,
        String reasonCode,
        String judgmentState,
        Message message,
        Object relatedEvent,
        LocalDate riskWeekStart,
        String googleEventId,
        OffsetDateTime doneAt) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public record Message(String text, boolean aiGenerated, boolean fallback) {
    }

    public static SuggestionPayload of(SuggestionView v) {
        return new SuggestionPayload(
                v.id(), v.type(), v.status(), v.targetDate(),
                v.start().atZone(SEOUL).toOffsetDateTime(),
                v.end().atZone(SEOUL).toOffsetDateTime(),
                v.durationMin(), v.title(), v.activityCode(), v.reasonCode(), v.judgmentState(),
                v.message() == null ? null : new Message(v.message(), v.aiGenerated(), v.fallback()),
                null,
                v.riskWeekStart(), v.googleEventId(),
                v.doneAt() == null ? null : v.doneAt().atZone(SEOUL).toOffsetDateTime());
    }
}
