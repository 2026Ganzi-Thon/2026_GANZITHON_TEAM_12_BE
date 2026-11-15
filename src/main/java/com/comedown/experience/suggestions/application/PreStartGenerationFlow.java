package com.comedown.experience.suggestions.application;

import com.comedown.account.contract.AccountDeletion;
import com.comedown.account.contract.LifestyleQuery;
import com.comedown.calendar.contract.CalendarDeadlineQuery;
import com.comedown.recommendation.contract.NewSuggestion;
import com.comedown.recommendation.contract.SuggestionCommands;
import com.comedown.recommendation.contract.SuggestionPlanning;
import com.comedown.recommendation.contract.SuggestionPlanning.TimeSlot;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.schedule.contract.WeekLoadView;
import com.comedown.schedule.contract.WeekLoads;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRE_START("미리 시작하기") 제안 생성. 위험 주에 마감이 있는 과제마다 빈 시간에 준비 블록을 하나 제안한다
 * (규칙은 recommendation의 {@link SuggestionPlanning}). 외부 호출이 없으므로 한 트랜잭션이며, 이미 진행 중인 제안이
 * 있거나 사용자가 건너뛴·되돌린 제안이 있는 마감 일정은 다시 만들지 않는다(여러 번 불러도 중복 제안이 생기지 않는다).
 * <p>
 * 문구는 규칙 기반이다(AI 도입 전, {@code fallback: true}). 제목에는 일정 제목을 쓰지 않는다.
 */
@Service
public class PreStartGenerationFlow {

    static final String TITLE = "과제 미리 시작하기";
    static final String MESSAGE = "과제 마감 전에 %d분만 먼저 시작해 볼까요?";
    /** 마감을 찾는 앞쪽 범위(주간 부하가 계산되는 범위와 맞춘다). */
    static final int HORIZON_WEEKS = 5;

    private final CalendarDeadlineQuery deadlines;
    private final SlotService slotService;
    private final AccountDeletion lock;
    private final WeekLoads weekLoads;
    private final LifestyleQuery lifestyle;
    private final SuggestionQuery suggestionQuery;
    private final SuggestionCommands suggestionCommands;
    private final SuggestionPlanning planning;
    private final ServiceTime time;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public PreStartGenerationFlow(CalendarDeadlineQuery deadlines, SlotService slotService, AccountDeletion lock,
            WeekLoads weekLoads, LifestyleQuery lifestyle, SuggestionQuery suggestionQuery,
            SuggestionCommands suggestionCommands, SuggestionPlanning planning, ServiceTime time) {
        this.deadlines = deadlines;
        this.slotService = slotService;
        this.lock = lock;
        this.weekLoads = weekLoads;
        this.lifestyle = lifestyle;
        this.suggestionQuery = suggestionQuery;
        this.suggestionCommands = suggestionCommands;
        this.planning = planning;
        this.time = time;
    }

    /** 새로 만든 제안을 돌려준다(없으면 빈 목록). */
    @Transactional
    public List<SuggestionView> refresh(long userId) {
        lock.lock(userId); // 동기화·다시 만들기가 겹쳐도 중복 제안이 생기지 않게 사용자별로 직렬화
        LocalDate today = time.today();
        LocalDate thisMonday = time.weekStartOf(today);
        var range = time.dateRange(today, thisMonday.plusWeeks(HORIZON_WEEKS).minusDays(1));
        Set<LocalDate> riskWeeks = new HashSet<>();
        for (WeekLoadView week : weekLoads.range(userId, thisMonday, HORIZON_WEEKS)) {
            if (planning.isPreStartRiskWeek(week.riskLevel())) {
                riskWeeks.add(week.weekStart());
            }
        }
        if (riskWeeks.isEmpty()) {
            return List.of();
        }
        List<SuggestionView> existing = suggestionQuery.search(userId, "PRE_START", null, null);
        Set<Long> alreadySuggested = new HashSet<>();
        for (SuggestionView s : existing) {
            if (s.relatedEventId() != null && blocksResuggestion(s.status())) {
                alreadySuggested.add(s.relatedEventId());
            }
        }
        LifestyleQuery.SleepWindow sleep = lifestyle.sleepWindow(userId);
        long blockMinutes = planning.preStartBlockLength().toMinutes();
        List<SuggestionView> created = new ArrayList<>();
        for (CalendarDeadlineQuery.Deadline deadline : deadlines.upcomingAssignments(userId, range.start(),
                range.endExclusive())) {
            LocalDate deadlineDate = time.toServiceDate(deadline.start());
            if (!riskWeeks.contains(time.weekStartOf(deadlineDate)) || alreadySuggested.contains(deadline.eventId())) {
                continue;
            }
            for (LocalDate day : planning.preStartCandidateDays(today, deadlineDate)) {
                long reservedThatDay = existing.stream()
                        .filter(s -> isLive(s.status()) && s.targetDate().equals(day)).count()
                        + created.stream().filter(s -> s.targetDate().equals(day)).count();
                if (reservedThatDay >= planning.maxSuggestionsPerDay()) {
                    continue;
                }
                List<TimeSlot> busy = slotService.busyOn(userId, day, existing, created, -1);
                var slots = planning.freeSlots(day, planning.preStartBlockLength(), busy, sleep.sleepStart(),
                        sleep.sleepEnd(), time.now(), 1);
                if (slots.isEmpty()) {
                    continue;
                }
                TimeSlot slot = slots.get(0);
                created.add(suggestionCommands.propose(new NewSuggestion(userId, "PRE_START", day, slot.start(),
                        slot.end(), TITLE, null, null, planning.preStartReasonCode(),
                        planning.preStartCalculationVersion(), MESSAGE.formatted(blockMinutes), false, true,
                        deadline.eventId(), time.weekStartOf(deadlineDate), planning.preStartExpiresAt(day))));
                break;
            }
        }
        return created;
    }

    /** 진행 중인 제안(빈 시간 계산과 하루 개수에 포함). */
    static boolean isLive(String status) {
        return "PROPOSED".equals(status) || "APPROVING".equals(status) || "APPROVED".equals(status);
    }

    /**
     * 같은 마감 일정에 새 제안을 만들지 않는 상태. 진행 중인 제안, 재시도할 수 있는 등록 실패(FAILED), 사용자가 직접
     * 건너뛴(DISMISSED)·되돌린(UNDONE) 제안. 만료(EXPIRED)는 사용자가 거절한 것이 아니므로 다시 제안할 수 있다
     * (구현 해석(확인 필요), API 문서 참고).
     */
    static boolean blocksResuggestion(String status) {
        return isLive(status) || "FAILED".equals(status) || "DISMISSED".equals(status) || "UNDONE".equals(status);
    }
}
