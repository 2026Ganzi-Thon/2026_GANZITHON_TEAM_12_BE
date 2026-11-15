package com.comedown.experience.reports.application;

import com.comedown.checkin.contract.DayIndex;
import java.time.LocalDate;

/**
 * 리포트 그래프의 주 = 온보딩 기준 n주차(1주차 = 1~7일차). 일정 부하 막대와 HRV 그래프가 같은 날짜 축을 쓴다.
 * 범위는 진행 중인 주를 포함한 최근 n개 주차라서 끝 날짜가 오늘보다 뒤일 수 있다.
 */
record ProgramWeeks(LocalDate onboardingDate, int fromWeek, int toWeek, int currentWeek) {

    static final int DAYS_PER_WEEK = 7;

    /** @param days 그래프 일수(7의 배수가 아니면 올림한 주 수) */
    static ProgramWeeks recent(LocalDate onboardingDate, LocalDate today, int days) {
        int current = weekOf(onboardingDate, today);
        int count = (days + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK;
        return new ProgramWeeks(onboardingDate, Math.max(1, current - count + 1), current, current);
    }

    static int weekOf(LocalDate onboardingDate, LocalDate date) {
        return (DayIndex.of(onboardingDate, date) - 1) / DAYS_PER_WEEK + 1;
    }

    LocalDate startOf(int week) {
        return onboardingDate.plusDays((long) DAYS_PER_WEEK * (week - 1));
    }

    LocalDate endOf(int week) {
        return startOf(week).plusDays(DAYS_PER_WEEK - 1L);
    }

    LocalDate from() {
        return startOf(fromWeek);
    }

    LocalDate to() {
        return endOf(toWeek);
    }
}
