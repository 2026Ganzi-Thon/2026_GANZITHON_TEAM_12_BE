package com.comedown.checkin.application;

import com.comedown.checkin.contract.CheckinDay;
import com.comedown.checkin.contract.CheckinQuery;
import com.comedown.checkin.contract.MeasurementSnapshot;
import com.comedown.checkin.domain.HrvMeasurement;
import com.comedown.checkin.domain.MorningCheck;
import com.comedown.checkin.infrastructure.persistence.HrvMeasurementRepository;
import com.comedown.checkin.infrastructure.persistence.MorningCheckRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 아침 체크 읽기 계약 구현. */
@Service
public class CheckinQueryService implements CheckinQuery {

    private final MorningCheckRepository morningChecks;
    private final HrvMeasurementRepository measurements;

    CheckinQueryService(MorningCheckRepository morningChecks, HrvMeasurementRepository measurements) {
        this.morningChecks = morningChecks;
        this.measurements = measurements;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CheckinDay> findDay(long userId, LocalDate date) {
        return morningChecks.findByUserIdAndCheckDate(userId, date)
                .map(check -> toDays(userId, List.of(check)).getFirst());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CheckinDay> findDaysThrough(long userId, LocalDate through) {
        return toDays(userId, morningChecks.findByUserIdAndCheckDateLessThanEqualOrderByCheckDateAsc(userId, through));
    }

    private List<CheckinDay> toDays(long userId, List<MorningCheck> checks) {
        if (checks.isEmpty()) {
            return List.of();
        }
        Map<Long, List<MeasurementSnapshot>> byCheck = measurements
                .findByUserIdAndMorningCheckIdInOrderByMeasuredAtAscIdAsc(
                        userId, checks.stream().map(MorningCheck::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(HrvMeasurement::getMorningCheckId,
                        Collectors.mapping(CheckinQueryService::toSnapshot, Collectors.toList())));
        return checks.stream()
                .map(c -> new CheckinDay(
                        c.getId(), c.getCheckDate(), c.getDayIndex(), c.getConditionScore(),
                        c.getAlcohol(), c.getSick(), c.getCaffeine(),
                        c.isConditionCompleted(), c.isConfounded(), c.getBreathingCompletedAt(),
                        byCheck.getOrDefault(c.getId(), List.of())))
                .toList();
    }

    private static MeasurementSnapshot toSnapshot(HrvMeasurement m) {
        return new MeasurementSnapshot(m.getId(), m.getMeasuredAt(), m.getQualityLevel().name(),
                m.getRmssdMs(), m.getLnRmssd(), m.getMeanHrBpm());
    }
}
