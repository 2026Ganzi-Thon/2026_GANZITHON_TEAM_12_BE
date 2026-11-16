package com.comedown.experience.suggestions.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.checkin.contract.DayIndex;
import com.comedown.recommendation.contract.SuggestionPlanning;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.recovery.contract.RecoveryQuery;
import com.comedown.schedule.contract.WeekLoadView;
import com.comedown.schedule.contract.WeekLoads;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 2축 판단(회복 × 이번 주 일정)을 account·recovery·schedule 읽기 계약의 스냅샷으로 조합한다. 규칙은 recommendation의
 * {@link SuggestionPlanning}에 있고 여기서는 읽어서 넘기기만 한다(쓰기 없음).
 * <p>
 * 회복 축은 판단하는 날의 대표 측정값을 온보딩 기준 직전 주차의 평균과 비교한다. 대표값이 있어도 교란 요인이 있는 날은
 * 판단에 쓰지 않는다(지난주 평균에서도 뺀다).
 */
@Component
public class RecoveryJudgment {

    /** @param overloaded 이번 주 일정이 평소보다 많은지 */
    public record Result(String judgment, boolean overloaded) {
    }

    private final AccountAccessQuery accountAccess;
    private final RecoveryQuery recoveryQuery;
    private final WeekLoads weekLoads;
    private final SuggestionPlanning planning;
    private final ServiceTime time;

    public RecoveryJudgment(AccountAccessQuery accountAccess, RecoveryQuery recoveryQuery, WeekLoads weekLoads,
            SuggestionPlanning planning, ServiceTime time) {
        this.accountAccess = accountAccess;
        this.recoveryQuery = recoveryQuery;
        this.weekLoads = weekLoads;
        this.planning = planning;
        this.time = time;
    }

    /**
     * @param state 판단할 날의 회복 상태(오늘 또는 가장 최근). 없으면 오늘을 측정값 없는 날로 본다
     * @return OK·OVERLOAD_OK·OVERLOAD_LOW·NORMAL_LOW·BUILDING·INSUFFICIENT
     */
    public String of(long userId, Optional<DailyRecoverySnapshot> state) {
        return evaluate(userId, state).judgment();
    }

    public Result evaluate(long userId, Optional<DailyRecoverySnapshot> state) {
        LocalDate today = time.today();
        boolean overloaded = planning.isOverloadedWeek(weekLoads.find(userId, time.currentWeekStart())
                .map(WeekLoadView::riskReasons).orElse(List.of()));
        LocalDate onboardingDate = accountAccess.findHealthDataAccess(userId)
                .filter(HealthDataAccess::onboardingCompleted)
                .map(a -> time.toServiceDate(a.onboardingCompletedAt()))
                .orElse(today); // 온보딩 전이면 1주차로 본다
        LocalDate day = state.map(DailyRecoverySnapshot::date).orElse(today);
        int week = planning.programWeek(DayIndex.of(onboardingDate, day));
        Double dayLn = state.filter(s -> !s.confounded()).map(DailyRecoverySnapshot::dailyLnRmssd).orElse(null);
        List<Double> priorWeekLn = List.of();
        if (week > 1 && dayLn != null) {
            LocalDate priorStart = onboardingDate.plusDays(7L * (week - 2));
            priorWeekLn = recoveryQuery.findRange(userId, priorStart, priorStart.plusDays(6)).stream()
                    .filter(s -> s.dailyLnRmssd() != null && !s.confounded())
                    .map(DailyRecoverySnapshot::dailyLnRmssd)
                    .toList();
        }
        return new Result(planning.judgment(week, dayLn, priorWeekLn, overloaded), overloaded);
    }
}
