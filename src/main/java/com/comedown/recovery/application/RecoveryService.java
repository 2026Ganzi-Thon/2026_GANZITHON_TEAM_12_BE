package com.comedown.recovery.application;

import com.comedown.checkin.contract.CheckinDay;
import com.comedown.checkin.contract.CheckinQuery;
import com.comedown.checkin.contract.MeasurementSnapshot;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.recovery.contract.RecoveryCommands;
import com.comedown.recovery.contract.RecoveryQuery;
import com.comedown.recovery.domain.HrvDailyState;
import com.comedown.recovery.domain.policy.MeasurementSample;
import com.comedown.recovery.domain.policy.RecoveryCalculator;
import com.comedown.recovery.domain.policy.RecoveryDayInput;
import com.comedown.recovery.infrastructure.persistence.HrvDailyStateRepository;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회복 재계산·조회. 입력은 checkin 읽기 계약의 스냅샷으로만 받고 checkin 테이블을 직접 읽지 않는다.
 */
@Service
public class RecoveryService implements RecoveryCommands, RecoveryQuery {

    private final CheckinQuery checkinQuery;
    private final HrvDailyStateRepository states;
    private final ServiceTime serviceTime;

    RecoveryService(CheckinQuery checkinQuery, HrvDailyStateRepository states, ServiceTime serviceTime) {
        this.checkinQuery = checkinQuery;
        this.states = states;
        this.serviceTime = serviceTime;
    }

    @Override
    @Transactional
    public void recalculate(long userId, LocalDate from, LocalDate through) {
        if (from.isAfter(through)) {
            throw new IllegalArgumentException("from은 through보다 늦을 수 없습니다: " + from + " > " + through);
        }
        // 범위 삭제를 먼저 해 같은 사용자의 동시 재계산을 직렬화한다.
        states.deleteFrom(userId, from);
        List<RecoveryDayInput> inputs = checkinQuery.findDaysThrough(userId, through).stream()
                .map(RecoveryService::toInput)
                .toList();
        Instant now = serviceTime.now();
        List<HrvDailyState> rows = RecoveryCalculator.calculate(inputs, through).stream()
                .filter(s -> !s.date().isBefore(from))
                .map(s -> HrvDailyState.of(userId, s, now))
                .toList();
        states.saveAll(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DailyRecoverySnapshot> findDay(long userId, LocalDate date) {
        return states.findByUserIdAndStateDate(userId, date).map(RecoveryService::toSnapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findRepresentativeMeasurementId(long userId, LocalDate date) {
        return checkinQuery.findDay(userId, date)
                .flatMap(day -> RecoveryDayInput.representative(samples(day)))
                .map(MeasurementSample::measurementId);
    }

    private static RecoveryDayInput toInput(CheckinDay day) {
        return RecoveryDayInput.of(day.date(), samples(day), day.conditionCompleted(), day.confounded());
    }

    private static List<MeasurementSample> samples(CheckinDay day) {
        return day.measurements().stream()
                .map(RecoveryService::toSample)
                .toList();
    }

    private static MeasurementSample toSample(MeasurementSnapshot m) {
        return new MeasurementSample(m.id(), m.measuredAt(), m.qualityLevel(), m.lnRmssd());
    }

    static DailyRecoverySnapshot toSnapshot(HrvDailyState s) {
        return new DailyRecoverySnapshot(
                s.getStateDate(), s.getDailyLnRmssd(), s.getDailyRmssdMs(), s.getMeasurementCount(),
                s.getQualityLevel(), s.isConfounded(), s.isBaselineEstablished(), s.getBaselineValidDays(),
                s.getBaselineMeanLn(), s.getBaselineSdLn(), s.isBaselineFrozen(), s.getTrendLn(),
                s.getChangePct(), s.getRecoveryStatus().name(), s.getCalculationVersion());
    }
}
