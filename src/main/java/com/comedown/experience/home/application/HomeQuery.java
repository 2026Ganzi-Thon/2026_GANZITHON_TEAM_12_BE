package com.comedown.experience.home.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.checkin.contract.CheckinDay;
import com.comedown.checkin.contract.CheckinQuery;
import com.comedown.checkin.contract.DayIndex;
import com.comedown.recommendation.contract.SuggestionPayload;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.recovery.contract.RecoveryQuery;
import com.comedown.safety.contract.SafetyQuery;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 홈(B1) 조합. account·checkin·recovery·recommendation·safety 읽기 계약의 스냅샷만 쓰고 테이블을 직접 읽지 않는다.
 * 읽기 전용이라 회복 상태·추천을 계산하거나 만들지 않는다.
 * <p>
 * 아직 비어 있는 값: thisWeekLoad(schedule 계약), plan(subscription 계약), judgment(추천 임계값 확정 후).
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

    public HomeQuery(AccountAccessQuery accountAccess, CheckinQuery checkinQuery, RecoveryQuery recoveryQuery,
            SuggestionQuery suggestionQuery, SafetyQuery safetyQuery, ServiceTime serviceTime) {
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
        Optional<CheckinDay> todayCheck = checkinQuery.findDay(userId, today);
        Optional<DailyRecoverySnapshot> latest = recoveryQuery.findLatestOnOrBefore(userId, today);
        boolean established = latest.map(DailyRecoverySnapshot::baselineEstablished).orElse(false);

        List<SuggestionPayload> suggestions = suggestionQuery.search(userId, null, null, today).stream()
                .filter(s -> !HIDDEN_STATUSES.contains(s.status()))
                .map(SuggestionPayload::of)
                .toList();

        return new HomeView(
                accountAccess.findNickname(userId).orElse(null),
                DayIndex.of(serviceTime.toServiceDate(access.onboardingCompletedAt()), today),
                established ? "ESTABLISHED" : "BUILDING",
                new HomeView.TodayCheck(checkStatus(todayCheck)),
                recovery(latest, todayCheck, today),
                null,
                null,
                suggestions,
                new HomeView.Safety(safetyQuery.shouldShow(userId)),
                null);
    }

    private static String checkStatus(Optional<CheckinDay> day) {
        if (day.isEmpty()) {
            return "NOT_STARTED";
        }
        return day.get().conditionCompleted() ? "DONE" : "IN_PROGRESS";
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
