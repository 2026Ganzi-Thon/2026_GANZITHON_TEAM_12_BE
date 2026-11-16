package com.comedown.recommendation.application;

import com.comedown.calendar.contract.CalendarEventSummaryQuery;
import com.comedown.calendar.contract.CalendarEventSummaryQuery.EventSummary;
import com.comedown.recommendation.contract.SuggestionPayload;
import com.comedown.recommendation.contract.SuggestionPayload.RelatedEvent;
import com.comedown.recommendation.contract.SuggestionPayloads;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.shared.time.ServiceTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 제안 응답 조립. 일정 요약(제목·시작·분류)은 calendar 읽기 계약에서 받는다. */
@Component
class SuggestionPayloadAssembler implements SuggestionPayloads {

    private final CalendarEventSummaryQuery events;
    private final ServiceTime time;

    SuggestionPayloadAssembler(CalendarEventSummaryQuery events, ServiceTime time) {
        this.events = events;
        this.time = time;
    }

    @Override
    public List<SuggestionPayload> of(long userId, List<SuggestionView> views) {
        Set<Long> eventIds = views.stream().map(SuggestionView::relatedEventId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, EventSummary> summaries = eventIds.isEmpty() ? Map.of() : events.findByIds(userId, eventIds);
        return views.stream()
                .map(v -> SuggestionPayload.of(v,
                        v.relatedEventId() == null ? null : related(summaries.get(v.relatedEventId()))))
                .toList();
    }

    private RelatedEvent related(EventSummary e) {
        return e == null ? null : new RelatedEvent(e.id(), e.title(), time.toResponse(e.start()), e.category());
    }
}
