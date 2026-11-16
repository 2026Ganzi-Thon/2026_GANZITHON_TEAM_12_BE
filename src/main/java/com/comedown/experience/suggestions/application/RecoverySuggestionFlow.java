package com.comedown.experience.suggestions.application;

import com.comedown.account.contract.AccountDeletion;
import com.comedown.account.contract.LifestyleQuery;
import com.comedown.checkin.contract.CheckinDay;
import com.comedown.checkin.contract.CheckinQuery;
import com.comedown.recommendation.contract.NewSuggestion;
import com.comedown.recommendation.contract.SuggestionCommands;
import com.comedown.recommendation.contract.SuggestionPlanning;
import com.comedown.recommendation.contract.SuggestionPlanning.TimeSlot;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.recovery.contract.RecoveryQuery;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오늘의 회복 추천(RECOVERY) 만들기. 컨디션까지 입력한 날, 기준선이 확정된 뒤 회복이 평소보다 낮을 때 선호 활동 하나를
 * 오늘의 가장 이른 빈 시간에 제안한다(규칙은 recommendation의 {@link SuggestionPlanning}).
 * 외부 호출이 없는 한 트랜잭션이고, 오늘 회복 추천이 이미 있으면(건너뛴 것 포함) 다시 만들지 않으므로 여러 번 불러도
 * 하루 하나다. 회복 상태는 계산하지 않고 저장된 값을 읽는다.
 */
@Service
public class RecoverySuggestionFlow {

    static final String RECOVERY = "RECOVERY";

    private final AccountDeletion lock;
    private final CheckinQuery checkinQuery;
    private final RecoveryQuery recoveryQuery;
    private final RecoveryJudgment judgment;
    private final LifestyleQuery lifestyle;
    private final SlotService slotService;
    private final SuggestionQuery suggestionQuery;
    private final SuggestionCommands suggestionCommands;
    private final SuggestionPlanning planning;
    private final ServiceTime time;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public RecoverySuggestionFlow(AccountDeletion lock, CheckinQuery checkinQuery, RecoveryQuery recoveryQuery,
            RecoveryJudgment judgment, LifestyleQuery lifestyle, SlotService slotService,
            SuggestionQuery suggestionQuery, SuggestionCommands suggestionCommands, SuggestionPlanning planning,
            ServiceTime time) {
        this.lock = lock;
        this.checkinQuery = checkinQuery;
        this.recoveryQuery = recoveryQuery;
        this.judgment = judgment;
        this.lifestyle = lifestyle;
        this.slotService = slotService;
        this.suggestionQuery = suggestionQuery;
        this.suggestionCommands = suggestionCommands;
        this.planning = planning;
        this.time = time;
    }

    /** 새로 만든 제안. 조건에 맞지 않거나 이미 있거나 빈 시간이 없으면 비어 있다. */
    @Transactional
    public Optional<SuggestionView> refresh(long userId) {
        lock.lock(userId); // 컨디션 저장·재측정·다시 만들기가 겹쳐도 하루 하나만 생기게 사용자별로 직렬화
        LocalDate today = time.today();
        boolean conditionDone = checkinQuery.findDay(userId, today).map(CheckinDay::conditionCompleted).orElse(false);
        if (!conditionDone) {
            return Optional.empty();
        }
        Optional<DailyRecoverySnapshot> state = recoveryQuery.findDay(userId, today);
        String judged = judgment.of(userId, state);
        if (state.isEmpty() || !planning.recommendsRecovery(judged)) {
            return Optional.empty();
        }
        List<SuggestionView> todays = suggestionQuery.search(userId, null, null, today);
        if (todays.stream().anyMatch(s -> RECOVERY.equals(s.type()))) {
            return Optional.empty();
        }
        long reserved = todays.stream().filter(s -> PreStartGenerationFlow.isLive(s.status())).count();
        List<String> preferred = lifestyle.preferredActivities(userId);
        if (reserved >= planning.maxSuggestionsPerDay() || preferred.isEmpty()) {
            return Optional.empty();
        }
        LifestyleQuery.SleepWindow sleep = lifestyle.sleepWindow(userId);
        List<TimeSlot> busy = slotService.busyOn(userId, today, todays, List.of(), -1);
        List<TimeSlot> slots = planning.freeSlots(today, planning.recoveryBlockLength(), busy, sleep.sleepStart(),
                sleep.sleepEnd(), time.now(), 1);
        if (slots.isEmpty()) {
            return Optional.empty();
        }
        TimeSlot slot = slots.get(0);
        String activity = planning.pickRecoveryActivity(preferred, today);
        return Optional.of(suggestionCommands.propose(new NewSuggestion(userId, RECOVERY, today, slot.start(),
                slot.end(), planning.recoveryTitle(activity), activity, judged, planning.recoveryReasonCode(),
                planning.recoveryCalculationVersion(), planning.recoveryMessage(activity, judged), false, true,
                null, null, planning.suggestionExpiresAt(today))));
    }
}
