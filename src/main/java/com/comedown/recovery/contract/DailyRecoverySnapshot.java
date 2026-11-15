package com.comedown.recovery.contract;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 하루 회복 상태 스냅샷(hrv_daily_states 1행). 기준선·추세는 ln(RMSSD) 단위이고 화면 표시는 exp로 ms 변환한다.
 *
 * @param qualityLevel   대표 측정 품질(GOOD·FAIR). 대표값이 없으면 null
 * @param recoveryStatus BUILDING·INSUFFICIENT·NORMAL·LOW
 */
public record DailyRecoverySnapshot(
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
        boolean baselineFrozen,
        Double trendLn,
        BigDecimal changePct,
        String recoveryStatus,
        String calculationVersion) {

    /** 7일 평균(ms) = exp(trend_ln). 추세가 없으면 null. */
    public Double trendRmssdMs() {
        return trendLn == null ? null : Math.exp(trendLn);
    }

    /** 정상 범위 하한(ms) = exp(평균 − 1SD). 기준선 전이면 null. */
    public Double normalLowerMs() {
        return baselineMeanLn == null ? null : Math.exp(baselineMeanLn - baselineSdLn);
    }

    /** 정상 범위 상한(ms) = exp(평균 + 1SD). 기준선 전이면 null. */
    public Double normalUpperMs() {
        return baselineMeanLn == null ? null : Math.exp(baselineMeanLn + baselineSdLn);
    }
}
