package com.comedown.checkin.contract;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * n일차 규칙. 온보딩 완료 서울 날짜를 1일차로 센다. 완료일 이전 날짜(자정 직전 측정 등)는 1일차로 둔다.
 * 아침 체크의 day_index 스냅샷과 화면의 현재 일차가 같은 규칙을 쓴다.
 */
public final class DayIndex {

    private DayIndex() {
    }

    public static int of(LocalDate onboardingDate, LocalDate date) {
        long days = ChronoUnit.DAYS.between(onboardingDate, date) + 1;
        return (int) Math.max(1, days);
    }
}
