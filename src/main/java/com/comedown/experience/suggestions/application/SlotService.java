package com.comedown.experience.suggestions.application;

import com.comedown.account.contract.LifestyleQuery;
import com.comedown.calendar.contract.CalendarEventReader;
import com.comedown.calendar.contract.CalendarEventSnapshot;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.recommendation.domain.policy.FreeSlotFinder;
import com.comedown.recommendation.domain.policy.FreeSlotFinder.Interval;
import com.comedown.recommendation.domain.policy.PrepareAheadPolicy;
import com.comedown.shared.time.ServiceTime;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 하루의 빈 시간 조회(일정 + 진행 중인 제안 + 수면 시간대). 쓰기 없이 읽기만 한다. */
@Service
class SlotService {

    static final int MAX_SLOTS = 5;

    private final CalendarEventReader events;
    private final LifestyleQuery lifestyle;
    private final ServiceTime time;

    SlotService(CalendarEventReader events, LifestyleQuery lifestyle, ServiceTime time) {
        this.events = events;
        this.lifestyle = lifestyle;
        this.time = time;
    }

    /**
     * 그 날 막힌 시간: 시간 일정 + 진행 중인 제안(다른 제안과도 겹치지 않게).
     *
     * @param ignoreSuggestionId 막힌 시간에서 뺄 제안 id(자기 자신). 없으면 -1
     */
    @Transactional(readOnly = true)
    List<Interval> busyOn(long userId, LocalDate day, List<SuggestionView> existing, List<SuggestionView> created,
            long ignoreSuggestionId) {
        var dayRange = time.dateRange(day, day);
        List<Interval> busy = new ArrayList<>();
        for (CalendarEventSnapshot e : events.findOverlapping(userId, dayRange)) {
            if (!e.allDay() && !e.cancelled() && !e.transparent()) {
                busy.add(new Interval(e.start(), e.end()));
            }
        }
        for (SuggestionView s : existing) {
            if (s.id() != ignoreSuggestionId && PreStartGenerationFlow.isLive(s.status())
                    && s.targetDate().equals(day)) {
                busy.add(new Interval(s.start(), s.end()));
            }
        }
        for (SuggestionView s : created) {
            if (s.targetDate().equals(day)) {
                busy.add(new Interval(s.start(), s.end()));
            }
        }
        return busy;
    }

    /** 같은 날·같은 길이로 넣을 수 있는 빈 시간(최대 5개). */
    @Transactional(readOnly = true)
    List<Interval> alternatives(SuggestionView suggestion, List<SuggestionView> sameUserSuggestions) {
        LifestyleQuery.SleepWindow sleep = lifestyle.sleepWindow(suggestion.userId());
        List<Interval> busy = busyOn(suggestion.userId(), suggestion.targetDate(), sameUserSuggestions, List.of(),
                suggestion.id());
        return FreeSlotFinder.find(suggestion.targetDate(), ServiceTime.ZONE,
                Duration.ofMinutes(suggestion.durationMin()), busy, sleep.sleepStart(), sleep.sleepEnd(),
                PrepareAheadPolicy.MIN_GAP, time.now(), MAX_SLOTS);
    }

    /** 다른 시간으로 승인하려는 구간이 같은 날·같은 길이로 빈 시간에 들어가는지. */
    @Transactional(readOnly = true)
    boolean fits(SuggestionView suggestion, Interval wanted, List<SuggestionView> sameUserSuggestions) {
        if (!wanted.end().equals(wanted.start().plusSeconds(suggestion.durationMin() * 60))) {
            return false;
        }
        if (!time.toServiceDate(wanted.start()).equals(suggestion.targetDate())
                || wanted.start().isBefore(time.now())) {
            return false;
        }
        LifestyleQuery.SleepWindow sleep = lifestyle.sleepWindow(suggestion.userId());
        List<Interval> busy = busyOn(suggestion.userId(), suggestion.targetDate(), sameUserSuggestions, List.of(),
                suggestion.id());
        return FreeSlotFinder.fits(suggestion.targetDate(), ServiceTime.ZONE, wanted, busy, sleep.sleepStart(),
                sleep.sleepEnd(), PrepareAheadPolicy.MIN_GAP);
    }
}
