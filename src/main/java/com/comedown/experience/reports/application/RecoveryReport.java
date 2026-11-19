package com.comedown.experience.reports.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/reports/recovery} 응답(API 명세 7절). 잠금(locked)이면 unlock 외 항목은 null이다.
 *
 * @param unavailableReason 건강정보 동의가 없으면 CONSENT_REQUIRED, 그 외 null
 * @param recoveryStatus    오늘 이하 가장 최근 회복 상태(NORMAL·LOW·INSUFFICIENT). "낮음" 칩은 %가 아니라 이 값으로 정한다
 * @param loadVsUsualPct    진행 중인 n주차의 일정 부하 평소 대비(직전 4개 주차 평균 대비). 캘린더 미연결·schedule 계약
 *                          구현(일정축) 전에는 null
 * @param interpretation    회복 해석 문구. AI 도입 전까지 규칙 기반 문구(fallback: true)
 */
public record RecoveryReport(
        boolean locked,
        String unavailableReason,
        Unlock unlock,
        Graph graph,
        BigDecimal hrvVsUsualPct,
        String recoveryStatus,
        String latestQuality,
        BigDecimal loadVsUsualPct,
        Interpretation interpretation,
        RangePosition rangePosition) {

    public record Unlock(int requiredDays, int validDays) {
    }

    /**
     * @param from      첫 주차 시작 날짜(온보딩 기준 n주차 경계)
     * @param to        진행 중인 주차의 끝 날짜. 오늘보다 뒤일 수 있다(오늘 이후는 HRV 항목 없음)
     * @param loadWeeks 주간 일정 부하 막대. 범위 안 주차마다 하나씩 항상 들어간다
     */
    public record Graph(LocalDate from, LocalDate to, List<Point> points, List<TrendPoint> trend,
            List<Band> normalBand, Range normalRange, List<LoadWeek> loadWeeks) {
    }

    /**
     * 온보딩 기준 n주차 일정 부하 막대.
     *
     * @param week      n주차(1 = 1~7일차)
     * @param current   오늘이 들어 있는 주차
     * @param loadScore load-v1 부하 점수(소수 첫째 자리). 캘린더 미연결·schedule 계약 구현 전에는 null
     */
    public record LoadWeek(int week, LocalDate weekStart, LocalDate weekEnd, boolean current, Double loadScore) {
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
     * @param load                      일정 부하 위치(부하 점수). 캘린더 미연결·주차 표본 4개 미만·최저 = 최고면 null
     * @param recoveryUnavailableReason recovery가 null인 이유(INSUFFICIENT_DATA). 공개 전이면 null
     * @param loadUnavailableReason     load가 null인 이유(INSUFFICIENT_DATA). 공개 전이면 null
     */
    public record RangePosition(boolean available, LocalDate availableFrom, RangeValue recovery, RangeValue load,
            String recoveryUnavailableReason, String loadUnavailableReason) {
    }

    /** @param position 0~100(정수 반올림), low·high는 표시값(회복은 ms, 일정 부하는 부하 점수) */
    public record RangeValue(long position, double low, double high) {
    }
}
