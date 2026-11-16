package com.comedown.experience.home.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.checkin.contract.CheckinDay;
import com.comedown.checkin.contract.CheckinQuery;
import com.comedown.checkin.contract.DayIndex;
import com.comedown.experience.suggestions.application.RecoveryJudgment;
import com.comedown.recommendation.contract.SuggestionPayload;
import com.comedown.recommendation.contract.SuggestionPayloads;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.recovery.contract.RecoveryQuery;
import com.comedown.safety.contract.SafetyQuery;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.schedule.contract.WeekLoads;
import com.comedown.shared.time.ServiceTime;
import com.comedown.subscription.contract.SubscriptionQuery;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 홈(B1) 조합. account·checkin·recovery·recommendation·safety 읽기 계약의 스냅샷만 쓰고 테이블을 직접 읽지 않는다.
 * 읽기 전용이라 회복 상태·추천을 계산하거나 만들지 않는다.
 * 건강정보 동의가 유효하지 않으면(철회하고 기록을 남긴 경우 포함) 회복·컨디션·기준선은 기록이 없을 때와 같게 준다.
 */
@Service
public class HomeQuery {

    static final int BASELINE_REQUIRED_DAYS = 7;
    /** 홈 "오늘의 추천"에서 빼는 상태. */
    static final Set<String> HIDDEN_STATUSES = Set.of("DISMISSED", "EXPIRED", "UNDONE");

    private final AccountAccessQuery accountAccess;
    private final CheckinQuery checkinQuery;
    private final RecoveryQuery recoveryQuery;
    private final SuggestionQuery suggestionQuery;
    private final SafetyQuery safetyQuery;
    private final ServiceTime serviceTime;
    private final WeekLoads weekLoads;
    private final SubscriptionQuery subscriptionQuery;
    private final SuggestionPayloads suggestionPayloads;
    private final RecoveryJudgment judgment;

    public HomeQuery(AccountAccessQuery accountAccess, CheckinQuery checkinQuery, RecoveryQuery recoveryQuery,
            SuggestionQuery suggestionQuery, SafetyQuery safetyQuery, ServiceTime serviceTime, WeekLoads weekLoads,
            SubscriptionQuery subscriptionQuery, SuggestionPayloads suggestionPayloads, RecoveryJudgment judgment) {
        this.weekLoads = weekLoads;
        this.subscriptionQuery = subscriptionQuery;
        this.suggestionPayloads = suggestionPayloads;
        this.judgment = judgment;
        this.accountAccess = accountAccess;
        this.checkinQuery = checkinQuery;
        this.recoveryQuery = recoveryQuery;
        this.suggestionQuery = suggestionQuery;
        this.safetyQuery = safetyQuery;
        this.serviceTime = serviceTime;
    }

    /** 온보딩 완료 후에만(403 ONBOARDING_REQUIRED). */
    public HomeView home(long userId) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!access.onboardingCompleted()) {
            throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
        }
        LocalDate today = serviceTime.today();
        boolean healthDataVisible = access.sensitiveHrvConsented();
        Optional<CheckinDay> day = checkinQuery.findDay(userId, today);
        // 동의가 없으면 건강 기록 삭제 뒤와 같게 본다: 호흡 기록이 있는 체크만 남고 컨디션·측정은 없는 상태.
        Optional<CheckinDay> todayCheck = healthDataVisible ? day : Optional.empty();
        Optional<DailyRecoverySnapshot> latest = healthDataVisible
                ? recoveryQuery.findLatestOnOrBefore(userId, today) : Optional.empty();
        boolean established = latest.map(DailyRecoverySnapshot::baselineEstablished).orElse(false);

        List<SuggestionPayload> suggestions = suggestionPayloads.of(userId,
                suggestionQuery.search(userId, null, null, today).stream()
                        .filter(s -> !HIDDEN_STATUSES.contains(s.status()))
                        .toList());

        return new HomeView(
                accountAccess.findNickname(userId).orElse(null),
                DayIndex.of(serviceTime.toServiceDate(access.onboardingCompletedAt()), today),
                established ? "ESTABLISHED" : "BUILDING",
                new HomeView.TodayCheck(healthDataVisible ? checkStatus(todayCheck) : breathingOnlyStatus(day)),
                recovery(latest, todayCheck, today),
                weekLoads.find(userId, serviceTime.weekStartOf(today))
                        .map(w -> new HomeView.ThisWeekLoad(w.vsUsualPct(), w.riskLevel())).orElse(null),
                judgment.of(userId, latest),
                suggestions,
                new HomeView.Safety(safetyQuery.shouldShow(userId)),
                subscriptionQuery.find(userId).map(p -> new HomeView.Plan(p.plan(), p.daysLeft())).orElse(null));
    }

    private static String checkStatus(Optional<CheckinDay> day) {
        if (day.isEmpty()) {
            return "NOT_STARTED";
        }
        return day.get().conditionCompleted() ? "DONE" : "IN_PROGRESS";
    }

    /** 건강정보 동의가 없을 때: 호흡만 한 체크는 진행 중, 그 외는 시작 전(컨디션 완료 여부는 드러내지 않는다). */
    private static String breathingOnlyStatus(Optional<CheckinDay> day) {
        return day.filter(d -> d.breathingCompletedAt() != null).isPresent() ? "IN_PROGRESS" : "NOT_STARTED";
    }

    private static HomeView.Recovery recovery(Optional<DailyRecoverySnapshot> latest, Optional<CheckinDay> todayCheck,
            LocalDate today) {
        boolean established = latest.map(DailyRecoverySnapshot::baselineEstablished).orElse(false);
        boolean conditionDone = todayCheck.map(CheckinDay::conditionCompleted).orElse(false);
        boolean excludedToday = conditionDone && latest
                .filter(s -> s.date().equals(today))
                .map(s -> s.dailyLnRmssd() == null || s.confounded())
                .orElse(true);
        return new HomeView.Recovery(
                latest.map(DailyRecoverySnapshot::recoveryStatus).orElse("BUILDING"),
                established ? latest.get().changePct() : null,
                new HomeView.Baseline(latest.map(DailyRecoverySnapshot::baselineValidDays).orElse(0),
                        BASELINE_REQUIRED_DAYS, established),
                todayCheck.map(CheckinDay::conditionScore).orElse(null),
                excludedToday);
    }
}
