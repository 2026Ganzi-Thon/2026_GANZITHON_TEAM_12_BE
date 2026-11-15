package com.comedown.safety.contract;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 2주 연속 낮음 평가에 넘기는 하루 회복 상태(recovery 스냅샷에서 필요한 값만).
 *
 * @param hasRepresentative 그날 하루 대표값이 있는지(측정하지 않은 날은 판정에서 빠짐)
 * @param recoveryStatus    BUILDING·INSUFFICIENT·NORMAL·LOW
 */
public record RecoveryDayStatus(LocalDate date, boolean hasRepresentative, String recoveryStatus) {

    public RecoveryDayStatus {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(recoveryStatus, "recoveryStatus");
    }
}
