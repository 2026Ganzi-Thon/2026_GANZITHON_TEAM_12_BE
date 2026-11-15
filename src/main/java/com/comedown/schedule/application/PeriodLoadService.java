package com.comedown.schedule.application;

import com.comedown.calendar.contract.CalendarEventReader;
import com.comedown.schedule.contract.PeriodLoad;
import com.comedown.schedule.contract.PeriodLoadQuery;
import com.comedown.schedule.domain.LoadCalculator;
import com.comedown.schedule.domain.PeriodSummary;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 기간 요약 유스케이스. calendar 계약으로 읽기만 한다. */
@Service
class PeriodLoadService implements PeriodLoadQuery {

    private final CalendarEventReader events;
    private final ServiceTime time;

    PeriodLoadService(CalendarEventReader events, ServiceTime time) {
        this.events = events;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public PeriodLoad summarize(long userId, LocalDate from, LocalDate toInclusive) {
        LocalDate toExclusive = toInclusive.plusDays(1);
        // 마지막 날 밤을 넘기는 일정과 연속 일정일 계산을 위해 앞뒤를 조금 더 읽는다.
        var loadEvents = events.findOverlapping(userId, time.dateRange(from.minusDays(1), toExclusive)).stream()
                .map(LoadEventMapper::toLoadEvent).toList();
        PeriodSummary s = LoadCalculator.summarize(from, toExclusive, loadEvents);
        return new PeriodLoad(s.from(), s.to(), s.restDays(), s.lateNightCount(), s.totalHours(), s.weekendHours(),
                s.deadlineCount(), s.eventCount());
    }
}
