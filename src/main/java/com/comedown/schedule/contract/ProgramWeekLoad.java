package com.comedown.schedule.contract;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 온보딩 기준 n주차의 일정 부하({@link ProgramWeekLoadQuery}).
 *
 * @param week                     n주차(1 = 1~7일차)
 * @param start                    시작 서울 날짜
 * @param end                      끝 서울 날짜(포함, start + 6일)
 * @param loadScore                load-v1 부하 점수. 그 기간 일정을 읽을 수 없으면 null
 * @param vsUsualPct               직전 4개 주차 평균 대비 %(소수 첫째 자리). 못 구하면 null
 * @param vsUsualUnavailableReason vsUsualPct가 null인 이유: ZERO_BASELINE(직전 평균 0) 또는 INSUFFICIENT_DATA(직전 2주 미만)
 */
public record ProgramWeekLoad(
        int week,
        LocalDate start,
        LocalDate end,
        BigDecimal loadScore,
        BigDecimal vsUsualPct,
        String vsUsualUnavailableReason) {
}
