package com.comedown.schedule.contract;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 온보딩 기준 주(n주차)별 일정 부하 읽기 계약. 회복 리포트(C1)·28일차 비교 리포트의 부하 막대·평소 대비 %에 쓴다.
 * <p>
 * n주차 = 온보딩 완료 서울 날짜 + 7(n−1)일부터 7일(1주차 = 1~7일차). 0주차 이하는 온보딩 전 기간이며
 * 평소 대비 비교에만 쓴다. 캘린더 탭·홈의 월~일 주({@code weekly_loads})와는 별개다.
 * 부하 점수·평소 대비 산식은 계산 정책 1절(load-v1)과 같고, 비교 대상이 "직전 4개 n주차"인 점만 다르다.
 * <p>
 * 구현: {@code schedule.application.ProgramWeekLoadService}(일정축). bean이 없으면 리포트는 부하 값을 null로 둔다.
 */
public interface ProgramWeekLoadQuery {

    /**
     * @param onboardingDate 온보딩 완료 서울 날짜(1주차 시작)
     * @param fromWeek       첫 주차(1 이상)
     * @param toWeek         마지막 주차(포함). 앞으로의 날짜는 이미 잡힌 일정까지만 반영한다
     * @return 주차 순서대로 {@code toWeek − fromWeek + 1}개. 캘린더가 연결되지 않았으면 empty
     */
    Optional<List<ProgramWeekLoad>> find(long userId, LocalDate onboardingDate, int fromWeek, int toWeek);

    /**
     * 일정 부하의 내 범위 중 현재 위치(계산 정책 6절). 표본은 1주차~{@code currentWeek}의 부하 점수이며, 지난 주차는
     * 집계 대상 일정이 있던 주차만 센다(평소 대비의 "자료 있는 주차"와 같은 기준). 진행 중인 주차는 항상 센다.
     *
     * @param onboardingDate 온보딩 완료 서울 날짜(1주차 시작)
     * @param currentWeek    진행 중인 주차(1 이상). 현재 값은 이 주차의 부하 점수다
     * @return 캘린더가 연결되지 않았으면 empty
     */
    Optional<ProgramWeekLoadRange> range(long userId, LocalDate onboardingDate, int currentWeek);
}
