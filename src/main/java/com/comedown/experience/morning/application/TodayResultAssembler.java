package com.comedown.experience.morning.application;

import com.comedown.checkin.contract.CheckinDay;
import com.comedown.checkin.contract.CheckinQuery;
import com.comedown.checkin.contract.MeasurementSnapshot;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.recovery.contract.RecoveryQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * checkin·recovery 읽기 계약의 스냅샷으로 오늘 결과를 조합한다. 테이블을 직접 읽지 않는다.
 */
@Component
public class TodayResultAssembler {

    static final int BASELINE_REQUIRED_DAYS = 7;
    static final String BUILDING = "BUILDING";
    static final String ESTABLISHED = "ESTABLISHED";

    private final CheckinQuery checkinQuery;
    private final RecoveryQuery recoveryQuery;

    public TodayResultAssembler(CheckinQuery checkinQuery, RecoveryQuery recoveryQuery) {
        this.checkinQuery = checkinQuery;
        this.recoveryQuery = recoveryQuery;
    }

    /** 그날 체크와 회복 상태가 있어야 한다(컨디션 저장 직후 등). */
    public TodayResult assemble(long userId, LocalDate date) {
        CheckinDay day = checkinQuery.findDay(userId, date)
                .orElseThrow(() -> new IllegalStateException("아침 체크가 없습니다: " + date));
        return assemble(userId, day)
                .orElseThrow(() -> new IllegalStateException("회복 상태가 계산되지 않았습니다: " + date));
    }

    /**
     * 이미 읽은 체크로 결과를 조합한다. 컨디션까지 끝나지 않았거나 회복 상태가 아직 없으면 비어 있다(조회는 계산하지 않음).
     */
    public Optional<TodayResult> assembleIfCompleted(long userId, CheckinDay day) {
        return day.conditionCompleted() ? assemble(userId, day) : Optional.empty();
    }

    private Optional<TodayResult> assemble(long userId, CheckinDay day) {
        LocalDate date = day.date();
        Optional<DailyRecoverySnapshot> found = recoveryQuery.findDay(userId, date);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DailyRecoverySnapshot state = found.get();
        Optional<MeasurementSnapshot> representative = recoveryQuery
                .findRepresentativeMeasurementId(userId, date)
                .flatMap(id -> day.measurements().stream().filter(m -> m.id() == id).findFirst());

        boolean established = state.baselineEstablished();
        boolean eligible = state.dailyLnRmssd() != null && day.conditionCompleted() && !state.confounded();
        return Optional.of(new TodayResult(
                date,
                established ? ESTABLISHED : BUILDING,
                recovery(state),
                state.qualityLevel(),
                representative.map(MeasurementSnapshot::meanHrBpm).map(TodayResultAssembler::round1).orElse(null),
                !eligible,
                new TodayResult.Baseline(state.baselineValidDays(), BASELINE_REQUIRED_DAYS, established,
                        state.baselineFrozen()),
                day.conditionScore(),
                null,
                List.of()));
    }

    private static TodayResult.Recovery recovery(DailyRecoverySnapshot state) {
        if (!state.baselineEstablished()) {
            return new TodayResult.Recovery(state.recoveryStatus(), null, null, null);
        }
        Double trend = state.trendRmssdMs();
        return new TodayResult.Recovery(
                state.recoveryStatus(),
                state.changePct(),
                trend == null ? null : round1(trend),
                new TodayResult.NormalRange(round1(state.normalLowerMs()), round1(state.normalUpperMs())));
    }

    static Double round1(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
