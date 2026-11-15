package com.comedown.recommendation.application;

import com.comedown.recommendation.contract.SuggestionPlanning;
import com.comedown.recommendation.domain.policy.FreeSlotFinder;
import com.comedown.recommendation.domain.policy.FreeSlotFinder.Interval;
import com.comedown.recommendation.domain.policy.PrepareAheadPolicy;
import com.comedown.shared.time.ServiceTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** {@link SuggestionPlanning}을 recommendation 도메인 정책({@link FreeSlotFinder}, {@link PrepareAheadPolicy})으로 구현한다. */
@Service
class SuggestionPlanningService implements SuggestionPlanning {

    private final PrepareAheadPolicy policy;

    SuggestionPlanningService(
            @Value("${comedown.suggestions.pre-start.duration-minutes:30}") int durationMinutes,
            @Value("${comedown.suggestions.max-per-day:2}") int maxPerDay) {
        this.policy = new PrepareAheadPolicy(Duration.ofMinutes(durationMinutes), maxPerDay, ServiceTime.ZONE);
    }

    @Override
    public List<TimeSlot> freeSlots(LocalDate day, Duration length, List<TimeSlot> busy, LocalTime sleepStart,
            LocalTime sleepEnd, Instant notBefore, int limit) {
        return FreeSlotFinder.find(day, ServiceTime.ZONE, length, toIntervals(busy), sleepStart, sleepEnd,
                        PrepareAheadPolicy.MIN_GAP, notBefore, limit)
                .stream().map(i -> new TimeSlot(i.start(), i.end())).toList();
    }

    @Override
    public boolean fits(LocalDate day, TimeSlot wanted, List<TimeSlot> busy, LocalTime sleepStart,
            LocalTime sleepEnd) {
        return FreeSlotFinder.fits(day, ServiceTime.ZONE, new Interval(wanted.start(), wanted.end()),
                toIntervals(busy), sleepStart, sleepEnd, PrepareAheadPolicy.MIN_GAP);
    }

    @Override
    public boolean isPreStartRiskWeek(String riskLevel) {
        return policy.isRiskWeek(riskLevel);
    }

    @Override
    public List<LocalDate> preStartCandidateDays(LocalDate today, LocalDate deadlineDate) {
        return policy.candidateDays(today, deadlineDate);
    }

    @Override
    public Duration preStartBlockLength() {
        return policy.blockLength();
    }

    @Override
    public int maxSuggestionsPerDay() {
        return policy.maxPerDay();
    }

    @Override
    public Instant preStartExpiresAt(LocalDate targetDate) {
        return policy.expiresAt(targetDate);
    }

    @Override
    public String preStartReasonCode() {
        return PrepareAheadPolicy.REASON_CODE;
    }

    @Override
    public String preStartCalculationVersion() {
        return PrepareAheadPolicy.CALCULATION_VERSION;
    }

    private static List<Interval> toIntervals(List<TimeSlot> slots) {
        return slots.stream().map(s -> new Interval(s.start(), s.end())).toList();
    }
}
