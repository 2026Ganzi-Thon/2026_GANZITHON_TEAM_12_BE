package com.comedown.experience.home.application;

import com.comedown.recommendation.contract.SuggestionPayload;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET /api/home} 응답(API 명세 3절).
 *
 * @param phase            BUILDING(적격 측정 7일 미만) / ESTABLISHED
 * @param thisWeekLoad     이번 주 일정 부하. schedule 읽기 계약(일정축)이 생기기 전까지 null
 * @param judgment         2축 판단. 추천 임계값이 확정되기 전까지 null
 * @param todaySuggestions 오늘 날짜의 제안(건너뜀·만료·되돌림 제외)
 * @param plan             이용 자격. subscription 읽기 계약(일정축)이 생기기 전까지 null
 */
public record HomeView(
        String nickname,
        int dayIndex,
        String phase,
        TodayCheck todayCheck,
        Recovery recovery,
        Object thisWeekLoad,
        String judgment,
        List<SuggestionPayload> todaySuggestions,
        Safety safety,
        Object plan) {

    /** @param status NOT_STARTED·IN_PROGRESS·DONE */
    public record TodayCheck(String status) {
    }

    /**
     * @param status        가장 최근 회복 상태(오늘 기록이 없으면 그 전 기록 기준). 기록이 없으면 BUILDING
     * @param changePct     평소 대비 %(추세가 있을 때)
     * @param conditionScore 오늘 컨디션(입력 전이면 null)
     * @param excludedToday  오늘 컨디션까지 입력했는데 적격일이 아님(교란 요인·대표값 없음)
     */
    public record Recovery(String status, BigDecimal changePct, Baseline baseline, Integer conditionScore,
            boolean excludedToday) {
    }

    public record Baseline(int validDays, int required, boolean established) {
    }

    public record Safety(boolean show) {
    }
}
