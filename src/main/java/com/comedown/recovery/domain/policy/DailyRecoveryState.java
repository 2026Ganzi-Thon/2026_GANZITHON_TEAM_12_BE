package com.comedown.recovery.domain.policy;

import com.comedown.recovery.domain.RecoveryStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 하루치 회복 계산 결과. hrv_daily_states 한 행에 대응한다(computed_at은 저장할 때 채운다).
 * 기준선·추세는 ln(RMSSD) 단위이며 화면 표시만 exp로 ms 변환한다.
 */
public record DailyRecoveryState(
        LocalDate date,
        Double dailyLnRmssd,
        Double dailyRmssdMs,
        int measurementCount,
        String qualityLevel,
        boolean confounded,
        boolean baselineEstablished,
        int baselineValidDays,
        Double baselineMeanLn,
        Double baselineSdLn,
        int baselineSampleCount,
        boolean baselineFrozen,
        Double trendLn,
        BigDecimal changePct,
        RecoveryStatus recoveryStatus,
        String calculationVersion) {

    /** 정상 범위 하한(ln) = 기준선 평균 − 1SD. 기준선 전이면 null. */
    public Double normalLowerLn() {
        return baselineMeanLn == null ? null : baselineMeanLn - baselineSdLn;
    }

    /** 정상 범위 상한(ln) = 기준선 평균 + 1SD. 기준선 전이면 null. */
    public Double normalUpperLn() {
        return baselineMeanLn == null ? null : baselineMeanLn + baselineSdLn;
    }
}
