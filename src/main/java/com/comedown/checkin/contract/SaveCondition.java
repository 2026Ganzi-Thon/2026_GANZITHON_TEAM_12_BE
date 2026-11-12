package com.comedown.checkin.contract;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 컨디션 저장 명령.
 *
 * @param onboardingDate 온보딩 완료 서울 날짜(새 체크의 n일차 계산 기준)
 * @param checkDate      체크 날짜(서울, 보통 오늘)
 * @param score          내가 느끼는 컨디션 1~5
 */
public record SaveCondition(
        long userId,
        LocalDate onboardingDate,
        LocalDate checkDate,
        int score,
        boolean alcohol,
        boolean sick,
        boolean caffeine) {

    public SaveCondition {
        Objects.requireNonNull(onboardingDate, "onboardingDate");
        Objects.requireNonNull(checkDate, "checkDate");
        if (score < 1 || score > 5) {
            throw new IllegalArgumentException("컨디션은 1~5여야 합니다: " + score);
        }
    }
}
