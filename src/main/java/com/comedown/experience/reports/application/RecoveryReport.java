package com.comedown.experience.reports.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/reports/recovery} 응답(API 명세 7절). 잠금(locked)이면 unlock 외 항목은 null이다.
 *
 * @param unavailableReason 건강정보 동의가 없으면 CONSENT_REQUIRED, 그 외 null
 * @param loadVsUsualPct    이번 주 일정 부하 평소 대비. schedule 읽기 계약(일정축) 전까지 null
 * @param interpretation    회복 해석 문구. AI 도입 전까지 규칙 기반 문구(fallback: true)
 */
public record RecoveryReport(
        boolean locked,
        String unavailableReason,
        Unlock unlock,
        Graph graph,
        BigDecimal hrvVsUsualPct,
        String latestQuality,
        BigDecimal loadVsUsualPct,
        Interpretation interpretation,
        RangePosition rangePosition) {

    public record Unlock(int requiredDays, int validDays) {
    }

    /**
     * @param loadWeeks 주간 일정 부하 막대. schedule 읽기 계약(일정축) 전까지 빈 배열
     */
    public record Graph(LocalDate from, LocalDate to, List<Point> points, List<TrendPoint> trend,
            List<Band> normalBand, Range normalRange, List<Object> loadWeeks) {
    }

    /** @param excluded 교란일(회색) */
    public record Point(LocalDate date, double rmssdMs, boolean excluded, String qualityLevel) {
    }

    public record TrendPoint(LocalDate date, double rmssdMs) {
    }

    public record Band(LocalDate date, double lowerMs, double upperMs) {
    }

    public record Range(double lowerMs, double upperMs) {
    }

    public record Interpretation(String text, boolean aiGenerated, boolean fallback) {
    }

    /**
     * @param available                 28일차 이후이고 7일 추세 값이 20개 이상
     * @param availableFrom             공개 가능 날짜(온보딩 서울 날짜 + 27일)
     * @param recovery                  회복 위치. 최저 = 최고면 null
     * @param load                      일정 부하 위치. schedule 읽기 계약 전까지 null
     * @param recoveryUnavailableReason recovery가 null인 이유(INSUFFICIENT_DATA). 공개 전이면 null
     * @param loadUnavailableReason     load가 null인 이유(INSUFFICIENT_DATA). 공개 전이면 null
     */
    public record RangePosition(boolean available, LocalDate availableFrom, RangeValue recovery, RangeValue load,
            String recoveryUnavailableReason, String loadUnavailableReason) {
    }

    /** @param position 0~100(정수 반올림), low·high는 ms 표시값 */
    public record RangeValue(long position, double low, double high) {
    }
}
