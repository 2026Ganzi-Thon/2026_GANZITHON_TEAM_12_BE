package com.comedown.cbi.contract;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CBI 응답 1회의 결과 스냅샷(원래 선택 없이 점수·수준만).
 *
 * @param round         INITIAL·DAY28
 * @param personalScore 몸과 마음의 피로(0~100, 소수 둘째 자리)
 * @param personalLevel LOW·HIGH·VERY_HIGH
 * @param workScore     일·공부로 인한 피로(0~100, 소수 둘째 자리)
 * @param workLevel     LOW·HIGH·VERY_HIGH
 * @param highRisk      어느 하위척도든 75 이상
 */
public record CbiResult(
        long id,
        String round,
        String questionnaireVersion,
        Instant takenAt,
        BigDecimal personalScore,
        String personalLevel,
        BigDecimal workScore,
        String workLevel,
        boolean highRisk) {
}
