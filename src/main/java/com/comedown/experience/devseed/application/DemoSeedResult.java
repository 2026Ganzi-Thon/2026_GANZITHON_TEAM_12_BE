package com.comedown.experience.devseed.application;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code POST /api/dev/seed} 응답.
 *
 * @param onboardingDate        온보딩 완료 서울 날짜(1일차)
 * @param today                 서울 기준 오늘
 * @param dayIndex              오늘의 일차
 * @param seededDays            기록을 넣은 날 수(측정 + 컨디션)
 * @param latestRecoveryStatus  마지막으로 기록을 넣은 날의 회복 상태
 * @param cbiRounds             넣은 CBI 회차
 * @param notSeeded             계약이 아직 없어 넣지 못한 영역(SCHEDULE: 캘린더 일정·주간 부하)
 */
public record DemoSeedResult(
        String scenario,
        LocalDate onboardingDate,
        LocalDate today,
        int dayIndex,
        int seededDays,
        String latestRecoveryStatus,
        List<String> cbiRounds,
        List<String> notSeeded) {
}
