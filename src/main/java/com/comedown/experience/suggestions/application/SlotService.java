package com.comedown.experience.suggestions.application;

import com.comedown.account.contract.LifestyleQuery;
import com.comedown.calendar.contract.CalendarEventReader;
import com.comedown.calendar.contract.CalendarEventSnapshot;
import com.comedown.recommendation.contract.SuggestionPlanning;
import com.comedown.recommendation.contract.SuggestionPlanning.TimeSlot;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.shared.time.InstantRange;
import com.comedown.shared.time.ServiceTime;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 하루의 빈 시간 조회(일정 + 진행 중인 제안 + 수면 시간대)와 승인 직전 재확인. 쓰기 없이 읽기만 한다. */
@Service
class SlotService {

    static final int MAX_SLOTS = 5;

    private final CalendarEventReader events;
    private final LifestyleQuery lifestyle;
    private final SuggestionPlanning planning;
    private final ServiceTime time;

    SlotService(CalendarEventReader events, LifestyleQuery lifestyle, SuggestionPlanning planning,
            ServiceTime time) {
        this.events = events;
        this.lifestyle = lifestyle;
        this.planning = planning;
        this.time = time;
    }

    /**
     * 그 날 막힌 시간: 시간 일정 + 진행 중인 제안(다른 제안과도 겹치지 않게).
     *
     * @param ignoreSuggestionId 막힌 시간에서 뺄 제안 id(자기 자신). 없으면 -1
     */
    @Transactional(readOnly = true)
    List<TimeSlot> busyOn(long userId, LocalDate day, List<SuggestionView> existing, List<SuggestionView> created,
            long ignoreSuggestionId) {
        var dayRange = time.dateRange(day, day);
        List<TimeSlot> busy = new ArrayList<>();
        for (CalendarEventSnapshot e : events.findOverlapping(userId, dayRange)) {
            if (blocksTime(e)) {
                busy.add(new TimeSlot(e.start(), e.end()));
            }
        }
        for (SuggestionView s : existing) {
            if (s.id() != ignoreSuggestionId && PreStartGenerationFlow.isLive(s.status())
                    && s.targetDate().equals(day)) {
                busy.add(new TimeSlot(s.start(), s.end()));
            }
        }
        for (SuggestionView s : created) {
            if (s.targetDate().equals(day)) {
                busy.add(new TimeSlot(s.start(), s.end()));
            }
        }
        return busy;
    }

    /** 같은 날·같은 길이로 넣을 수 있는 빈 시간(최대 5개). */
    @Transactional(readOnly = true)
    List<TimeSlot> alternatives(SuggestionView suggestion, List<SuggestionView> sameUserSuggestions) {
        LifestyleQuery.SleepWindow sleep = lifestyle.sleepWindow(suggestion.userId());
        List<TimeSlot> busy = busyOn(suggestion.userId(), suggestion.targetDate(), sameUserSuggestions, List.of(),
                suggestion.id());
        return planning.freeSlots(suggestion.targetDate(), Duration.ofMinutes(suggestion.durationMin()), busy,
                sleep.sleepStart(), sleep.sleepEnd(), time.now(), MAX_SLOTS);
    }

    /** 다른 시간으로 승인하려는 구간이 같은 날·같은 길이로 빈 시간에 들어가는지. */
    @Transactional(readOnly = true)
    boolean fits(SuggestionView suggestion, TimeSlot wanted, List<SuggestionView> sameUserSuggestions) {
        if (!wanted.end().equals(wanted.start().plusSeconds(suggestion.durationMin() * 60))) {
            return false;
        }
        if (!time.toServiceDate(wanted.start()).equals(suggestion.targetDate())
                || wanted.start().isBefore(time.now())) {
            return false;
        }
        LifestyleQuery.SleepWindow sleep = lifestyle.sleepWindow(suggestion.userId());
        List<TimeSlot> busy = busyOn(suggestion.userId(), suggestion.targetDate(), sameUserSuggestions, List.of(),
                suggestion.id());
        return planning.fits(suggestion.targetDate(), wanted, busy, sleep.sleepStart(), sleep.sleepEnd());
    }

    /** 제안한 시작 시각이 이미 지났는지(제안한 시간 그대로 승인할 때의 재확인). */
    boolean alreadyStarted(SuggestionView suggestion) {
        return suggestion.start().isBefore(time.now());
    }

    /**
     * 제안을 만든 뒤 같은 시간에 일정이 생겼는지(제안한 시간 그대로 승인할 때의 재확인). 저장된(마지막으로 동기화한) 일정과
     * 실제로 겹치는지만 본다. 다른 제안과의 겹침은 승인 시작의 사용자 잠금 안에서 따로 검사하고, 앞뒤 간격·수면 시간대는
     * 제안을 만들 때의 조건이라 다시 적용하지 않는다(구현 해석(확인 필요), API 문서 5절).
     */
    @Transactional(readOnly = true)
    boolean takenByEvent(SuggestionView suggestion) {
        return events.findOverlapping(suggestion.userId(), new InstantRange(suggestion.start(), suggestion.end()))
                .stream().anyMatch(SlotService::blocksTime);
    }

    /** 시간을 막는 일정: 시간 일정이고 취소·"한가함"이 아닌 것(종일 일정은 막지 않는다). */
    private static boolean blocksTime(CalendarEventSnapshot e) {
        return !e.allDay() && !e.cancelled() && !e.transparent();
    }
}
