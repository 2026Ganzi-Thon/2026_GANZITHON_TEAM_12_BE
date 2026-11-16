package com.comedown.experience.suggestions.application;

import com.comedown.recommendation.contract.SuggestionPlanning;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.schedule.contract.WeekLoadView;
import com.comedown.schedule.contract.WeekLoads;
import com.comedown.shared.time.ServiceTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 2축 판단(회복 × 이번 주 일정)을 recovery·schedule 읽기 계약의 스냅샷으로 조합한다. 규칙은 recommendation의
 * {@link SuggestionPlanning}에 있고 여기서는 읽어서 넘기기만 한다(쓰기 없음).
 */
@Component
public class RecoveryJudgment {

    private final WeekLoads weekLoads;
    private final SuggestionPlanning planning;
    private final ServiceTime time;

    public RecoveryJudgment(WeekLoads weekLoads, SuggestionPlanning planning, ServiceTime time) {
        this.weekLoads = weekLoads;
        this.planning = planning;
        this.time = time;
    }

    /**
     * @param state 판단에 쓸 회복 상태(오늘 또는 가장 최근). 없으면 기준선 만드는 중으로 본다
     * @return OK·OVERLOAD_OK·OVERLOAD_LOW·NORMAL_LOW·BUILDING·INSUFFICIENT
     */
    public String of(long userId, Optional<DailyRecoverySnapshot> state) {
        boolean established = state.map(DailyRecoverySnapshot::baselineEstablished).orElse(false);
        if (!established) {
            return planning.judgment(false, null, false); // 일정 부하를 읽지 않아도 결과가 같다
        }
        List<String> reasons = weekLoads.find(userId, time.currentWeekStart())
                .map(WeekLoadView::riskReasons).orElse(List.of());
        return planning.judgment(true, state.get().recoveryStatus(), planning.isOverloadedWeek(reasons));
    }
}
