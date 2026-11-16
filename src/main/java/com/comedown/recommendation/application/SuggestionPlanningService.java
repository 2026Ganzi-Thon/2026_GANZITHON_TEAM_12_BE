package com.comedown.recommendation.application;

import com.comedown.recommendation.contract.SuggestionPlanning;
import com.comedown.recommendation.domain.policy.FreeSlotFinder;
import com.comedown.recommendation.domain.policy.FreeSlotFinder.Interval;
import com.comedown.recommendation.domain.policy.PrepareAheadPolicy;
import com.comedown.recommendation.domain.policy.RecoverySuggestionPolicy;
import com.comedown.shared.time.ServiceTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * {@link SuggestionPlanning}을 recommendation 도메인 정책({@link FreeSlotFinder}, {@link PrepareAheadPolicy},
 * {@link RecoverySuggestionPolicy})으로 구현한다.
 */
@Service
class SuggestionPlanningService implements SuggestionPlanning {

    private final PrepareAheadPolicy policy;
    private final RecoverySuggestionPolicy recovery;

    SuggestionPlanningService(
            @Value("${comedown.suggestions.pre-start.duration-minutes:30}") int durationMinutes,
            @Value("${comedown.suggestions.max-per-day:2}") int maxPerDay,
            @Value("${comedown.suggestions.recovery.duration-minutes:20}") int recoveryMinutes) {
        this.policy = new PrepareAheadPolicy(Duration.ofMinutes(durationMinutes), maxPerDay, ServiceTime.ZONE);
        this.recovery = new RecoverySuggestionPolicy(Duration.ofMinutes(recoveryMinutes));
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

    @Override
    public boolean isOverloadedWeek(List<String> weekRiskReasons) {
        return RecoverySuggestionPolicy.isOverloaded(weekRiskReasons);
    }

    @Override
    public String judgment(boolean baselineEstablished, String recoveryStatus, boolean overloaded) {
        return RecoverySuggestionPolicy.judgment(baselineEstablished, recoveryStatus, overloaded);
    }

    @Override
    public boolean recommendsRecovery(String judgment) {
        return RecoverySuggestionPolicy.recommends(judgment);
    }

    @Override
    public String pickRecoveryActivity(List<String> preferredActivities, LocalDate date) {
        return RecoverySuggestionPolicy.pickActivity(preferredActivities, date);
    }

    @Override
    public Duration recoveryBlockLength() {
        return recovery.blockLength();
    }

    @Override
    public String recoveryTitle(String activityCode) {
        return recovery.title(activityCode);
    }

    @Override
    public String recoveryMessage(String activityCode, String judgment) {
        return recovery.message(activityCode, judgment);
    }

    @Override
    public String recoveryReasonCode() {
        return RecoverySuggestionPolicy.REASON_CODE;
    }

    @Override
    public String recoveryCalculationVersion() {
        return RecoverySuggestionPolicy.CALCULATION_VERSION;
    }

    @Override
    public Instant suggestionExpiresAt(LocalDate targetDate) {
        return policy.expiresAt(targetDate);
    }

    private static List<Interval> toIntervals(List<TimeSlot> slots) {
        return slots.stream().map(s -> new Interval(s.start(), s.end())).toList();
    }
}
