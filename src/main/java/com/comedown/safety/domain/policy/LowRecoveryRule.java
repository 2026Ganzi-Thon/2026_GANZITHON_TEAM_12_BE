package com.comedown.safety.domain.policy;

import com.comedown.safety.contract.RecoveryDayStatus;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 2주 연속 낮음(LOW_RECOVERY_2W, 계산 정책 5절, recovery-v1).
 * 최근 14일(D−13 ~ D) 중 대표값이 있는 날이 7일 이상이고, 그 날들의 회복 상태가 모두 LOW.
 * 측정하지 않은 날은 판정에서 빠진다(낮음으로도 정상으로도 세지 않음).
 * 교란일도 대표값이 있으면 센다(검토결과 Q6 임시 해석).
 */
public final class LowRecoveryRule {

    static final int WINDOW_DAYS = 14;
    static final int MIN_MEASURED_DAYS = 7;
    static final String LOW = "LOW";

    private LowRecoveryRule() {
    }

    /**
     * @return 조건을 충족하면 낮음 구간의 첫 날짜(창 안에서 대표값이 있는 가장 이른 날), 아니면 비어 있음
     */
    public static Optional<LocalDate> evaluate(List<RecoveryDayStatus> days, LocalDate today) {
        LocalDate from = today.minusDays(WINDOW_DAYS - 1);
        List<RecoveryDayStatus> measured = days.stream()
                .filter(d -> !d.date().isBefore(from) && !d.date().isAfter(today))
                .filter(RecoveryDayStatus::hasRepresentative)
                .toList();
        if (measured.size() < MIN_MEASURED_DAYS) {
            return Optional.empty();
        }
        if (!measured.stream().allMatch(d -> LOW.equals(d.recoveryStatus()))) {
            return Optional.empty();
        }
        return measured.stream().map(RecoveryDayStatus::date).min(Comparator.naturalOrder());
    }
}
