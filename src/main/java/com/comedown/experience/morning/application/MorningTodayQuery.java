package com.comedown.experience.morning.application;

import com.comedown.checkin.contract.CheckinDay;
import com.comedown.checkin.contract.CheckinQuery;
import com.comedown.checkin.contract.DayIndex;
import com.comedown.checkin.contract.MeasurementSnapshot;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.recovery.contract.RecoveryQuery;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 오늘 아침 체크 조회. 읽기만 하며 체크 행을 만들거나 회복 상태를 계산하지 않는다.
 */
@Service
public class MorningTodayQuery {

    static final Set<String> USABLE_QUALITIES = Set.of("GOOD", "FAIR", "UNVERIFIED");

    private final CheckinQuery checkinQuery;
    private final RecoveryQuery recoveryQuery;
    private final TodayResultAssembler assembler;
    private final ServiceTime serviceTime;

    public MorningTodayQuery(CheckinQuery checkinQuery, RecoveryQuery recoveryQuery, TodayResultAssembler assembler,
            ServiceTime serviceTime) {
        this.checkinQuery = checkinQuery;
        this.recoveryQuery = recoveryQuery;
        this.assembler = assembler;
        this.serviceTime = serviceTime;
    }

    public MorningCheckToday today(long userId, LocalDate onboardingDate) {
        LocalDate today = serviceTime.today();
        Optional<CheckinDay> day = checkinQuery.findDay(userId, today);
        Optional<DailyRecoverySnapshot> latest = recoveryQuery.findLatestOnOrBefore(userId, today);
        boolean established = latest.map(DailyRecoverySnapshot::baselineEstablished).orElse(false);

        return new MorningCheckToday(
                today,
                DayIndex.of(onboardingDate, today),
                established ? TodayResultAssembler.ESTABLISHED : TodayResultAssembler.BUILDING,
                new MorningCheckToday.Steps(
                        measurementStep(userId, day),
                        new MorningCheckToday.Step(day.map(CheckinDay::conditionCompleted).orElse(false)),
                        new MorningCheckToday.Step(day.map(d -> d.breathingCompletedAt() != null).orElse(false))),
                new MorningCheckToday.Baseline(
                        latest.map(DailyRecoverySnapshot::baselineValidDays).orElse(0),
                        TodayResultAssembler.BASELINE_REQUIRED_DAYS),
                day.flatMap(d -> assembler.assembleIfCompleted(userId, d)).orElse(null));
    }

    private MorningCheckToday.MeasurementStep measurementStep(long userId, Optional<CheckinDay> day) {
        List<MeasurementSnapshot> measurements = day.map(CheckinDay::measurements).orElse(List.of());
        if (measurements.isEmpty()) {
            return new MorningCheckToday.MeasurementStep(false, null, null);
        }
        MeasurementSnapshot shown = displayed(userId, day.get(), measurements);
        return new MorningCheckToday.MeasurementStep(
                USABLE_QUALITIES.contains(shown.qualityLevel()), shown.id(), shown.qualityLevel());
    }

    /** 대표 측정(첫 GOOD·FAIR) → 가장 최근 수동 입력 → 가장 최근 측정(RETRY) 순으로 보여준다. */
    private MeasurementSnapshot displayed(long userId, CheckinDay day, List<MeasurementSnapshot> measurements) {
        Optional<Long> representativeId = recoveryQuery.findRepresentativeMeasurementId(userId, day.date());
        if (representativeId.isPresent()) {
            long id = representativeId.get();
            return measurements.stream().filter(m -> m.id() == id).findFirst().orElseThrow();
        }
        return measurements.reversed().stream()
                .filter(m -> "UNVERIFIED".equals(m.qualityLevel()))
                .findFirst()
                .orElse(measurements.getLast());
    }
}
