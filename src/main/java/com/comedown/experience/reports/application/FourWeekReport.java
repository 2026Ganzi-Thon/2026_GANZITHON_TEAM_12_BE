package com.comedown.experience.reports.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code GET /api/reports/four-week} 응답(API 명세 7절). available이 false면 보고서 항목은 null이다.
 *
 * @param cbiDue            28일차가 지났는데 DAY28 CBI를 아직 안 함 → 재응답 화면
 * @param availableFrom     공개 가능 날짜(온보딩 서울 날짜 + 27일)
 * @param unavailableReason 건강정보 동의가 없으면 CONSENT_REQUIRED, 그 외 null
 * @param load              일정 부하 비교. schedule 읽기 계약(일정축) 전까지 null
 * @param plan              이용 자격. subscription 읽기 계약(일정축) 전까지 null
 */
public record FourWeekReport(
        boolean available,
        boolean cbiDue,
        LocalDate availableFrom,
        String unavailableReason,
        Cbi cbi,
        Recovery recovery,
        Object load,
        Suggestions suggestions,
        RecoveryReport.Interpretation interpretation,
        Object plan) {

    /** @param initial 가입 때 응답(건너뛰었으면 null), diff = day28 − initial(initial이 없으면 null) */
    public record Cbi(Scores initial, Scores day28, Scores diff) {
    }

    /** 소수 첫째 자리. */
    public record Scores(BigDecimal personal, BigDecimal work) {
    }

    /**
     * 첫 주(1~7일차)와 마지막 주(22~28일차)의 적격 대표값 평균(ln 평균의 exp, ms).
     *
     * @param changePct (exp(마지막 주 ln 평균 − 첫 주 ln 평균) − 1) × 100. 어느 한 주라도 값이 없으면 null
     */
    public record Recovery(Double firstWeekAvgMs, Double lastWeekAvgMs, BigDecimal changePct) {
    }

    /** 4주(1~28일차) 동안 날짜가 걸린 제안 중 승인한 수와 "했어요" 수. */
    public record Suggestions(long approved, long done) {
    }
}
