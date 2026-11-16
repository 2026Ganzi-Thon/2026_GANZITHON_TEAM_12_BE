package com.comedown.experience.morning.application;

import com.comedown.recommendation.contract.SuggestionPayload;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 오늘 결과(API 명세 6절 TodayResult). 컨디션 저장 응답과 {@code GET /api/morning-checks/today}의 result에 쓴다.
 * ms 값은 ln 단위 계산값을 exp로 바꾼 표시값이며 소수 첫째 자리로 반올림한다.
 *
 * @param phase                BUILDING(유효 측정 7일 확보 전) / ESTABLISHED
 * @param qualityLevel         하루 대표 측정의 품질. 대표값이 없으면 null
 * @param rmssdMs              하루 대표 측정의 HRV(RMSSD, ms). 대표값이 없으면 null. 기준선 만드는 중에도 표시용으로 준다
 * @param heartRateBpm         하루 대표 측정의 심박수. 대표값이 없으면 null
 * @param excludedFromBaseline 오늘이 '내 평소' 계산에서 빠졌는지(교란 요인·대표값 없음·컨디션 미입력)
 * @param judgment             2축 판단(지난주 평균 대비 오늘 측정값 × 이번 주 일정). 1주차면 BUILDING
 * @param interpretation       회복 해석 한 줄. 기준선 확정 전(BUILDING)이면 null. AI 도입 전까지 규칙 기반 문구(fallback: true)
 * @param suggestions          그날의 RECOVERY 추천(건너뜀·만료·되돌림 제외). 없으면 빈 배열
 */
public record TodayResult(
        LocalDate date,
        String phase,
        Recovery recovery,
        String qualityLevel,
        Double rmssdMs,
        Double heartRateBpm,
        boolean excludedFromBaseline,
        Baseline baseline,
        Integer conditionScore,
        String judgment,
        Interpretation interpretation,
        List<SuggestionPayload> suggestions) {

    /**
     * @param status       BUILDING·INSUFFICIENT·NORMAL·LOW
     * @param changePct    평소 대비 %(추세가 있을 때)
     * @param trendRmssdMs 7일 평균(추세가 있을 때)
     * @param normalRange  정상 범위(기준선 확정 후)
     */
    public record Recovery(String status, BigDecimal changePct, Double trendRmssdMs, NormalRange normalRange) {
    }

    public record NormalRange(double lowerMs, double upperMs) {
    }

    /** API 공통 AI 문구 모양. */
    public record Interpretation(String text, boolean aiGenerated, boolean fallback) {
    }

    /**
     * @param validDays "기준선 만드는 중 n/7"의 n
     * @param required  초기 기준선에 필요한 적격일 수(7)
     */
    public record Baseline(int validDays, int required, boolean established, boolean frozen) {
    }
}
