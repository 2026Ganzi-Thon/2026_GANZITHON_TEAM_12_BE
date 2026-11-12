package com.comedown.experience.morning.application;

import java.time.LocalDate;

/**
 * {@code GET /api/morning-checks/today} 응답(API 명세 6절). 측정 탭 진입 시 화면 단계를 정한다.
 *
 * @param dayIndex 현재 n일차(온보딩 완료 서울 날짜 = 1일차)
 * @param phase    BUILDING(유효 측정 7일 확보 전) / ESTABLISHED
 * @param result   컨디션까지 끝났으면 오늘 결과(다시 들어와도 결과 화면), 아니면 null
 */
public record MorningCheckToday(
        LocalDate date,
        int dayIndex,
        String phase,
        Steps steps,
        Baseline baseline,
        TodayResult result) {

    /** 화면 순서: 측정 → 컨디션 → 호흡. */
    public record Steps(MeasurementStep measurement, Step condition, Step breathing) {
    }

    /**
     * @param done          쓸 수 있는 측정(GOOD·FAIR·UNVERIFIED)이 있음
     * @param measurementId 표시할 측정(대표 측정 → 수동 입력 → 가장 최근 측정 순). 측정이 없으면 null
     * @param qualityLevel  그 측정의 품질. RETRY면 재측정 안내
     */
    public record MeasurementStep(boolean done, Long measurementId, String qualityLevel) {
    }

    public record Step(boolean done) {
    }

    /**
     * @param validDays "기준선 만드는 중 n/7"의 n
     * @param required  초기 기준선에 필요한 적격일 수(7)
     */
    public record Baseline(int validDays, int required) {
    }
}
