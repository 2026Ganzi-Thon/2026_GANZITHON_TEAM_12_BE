package com.comedown.experience.onboarding.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.AccountCommands;
import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.account.contract.OnboardingCommands;
import com.comedown.checkin.contract.DayIndex;
import com.comedown.schedule.contract.WeekLoads;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import com.comedown.subscription.contract.SubscriptionCommands;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 온보딩 흐름 조합: 프로필 → CBI 건너뛰기 → 생활 정보 → 완료(체험 시작). 단계 값은 account만 쓴다. */
@Service
public class OnboardingFlow {

    static final String CALENDAR_AUTH_URL = "/oauth2/authorization/google-calendar";
    static final String SCHEDULE_REPORT = "SCHEDULE_REPORT";
    static final String ANALYSIS_REQUIRED_MESSAGE = "일정 분석을 먼저 끝내 주세요.";

    public record ProfileResult(String onboardingStep, String calendarAuthUrl) {
    }

    public record CompleteResult(String onboardingStep, int dayIndex) {
    }

    private final AccountCommands account;
    private final AccountAccessQuery accountAccess;
    private final OnboardingCommands onboarding;
    private final CalendarConnectionQuery calendar;
    private final WeekLoads weekLoads;
    private final SubscriptionCommands subscription;
    private final ServiceTime time;

    public OnboardingFlow(AccountCommands account, AccountAccessQuery accountAccess, OnboardingCommands onboarding,
            CalendarConnectionQuery calendar, WeekLoads weekLoads, SubscriptionCommands subscription,
            ServiceTime time) {
        this.account = account;
        this.accountAccess = accountAccess;
        this.onboarding = onboarding;
        this.calendar = calendar;
        this.weekLoads = weekLoads;
        this.subscription = subscription;
        this.time = time;
    }

    @Transactional
    public ProfileResult saveProfile(long userId, AccountCommands.Profile profile) {
        String step = account.saveProfile(userId, profile);
        return new ProfileResult(step, calendar.isConnected(userId) ? null : CALENDAR_AUTH_URL);
    }

    @Transactional
    public String skipCbi(long userId) {
        return onboarding.skipCbiStep(userId);
    }

    @Transactional
    public String saveSettings(long userId, AccountCommands.Settings settings) {
        return account.replaceSettings(userId, settings);
    }

    /**
     * 온보딩을 끝내고 무료 체험을 시작한다(둘이 함께 성공하거나 함께 실패한다).
     * 아직 완료 전이면 선행 조건을 다시 확인한다(API 명세 0절): 단계(프로필·CBI·생활 정보) → 캘린더 권한 → 일정 분석 결과.
     * 이미 완료한 사용자는 다시 확인하지 않고 처음 완료 결과를 돌려준다(나중에 캘린더를 해제해도 온보딩을 되돌리지 않는다).
     */
    @Transactional
    public CompleteResult complete(long userId) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!access.onboardingCompleted()) {
            requireReadyToComplete(userId, access.onboardingStep());
        }
        Instant completedAt = onboarding.completeOnboarding(userId);
        subscription.startTrial(userId, completedAt);
        return new CompleteResult("DONE", DayIndex.of(time.toServiceDate(completedAt), time.today()));
    }

    /**
     * 일정 분석 결과는 동기화가 저장하는 이번 주 주간 부하로 확인한다. 일정이 0건이어도 동기화했다면 행이 있으므로
     * 완료할 수 있다.
     */
    private void requireReadyToComplete(long userId, String step) {
        if (!SCHEDULE_REPORT.equals(step)) {
            throw new ApiException(ErrorCode.ONBOARDING_STEP_INVALID);
        }
        if (!calendar.isConnected(userId)) {
            throw new ApiException(ErrorCode.CALENDAR_NOT_CONNECTED);
        }
        if (weekLoads.find(userId, time.currentWeekStart()).isEmpty()) {
            throw new ApiException(ErrorCode.ONBOARDING_STEP_INVALID, ANALYSIS_REQUIRED_MESSAGE);
        }
    }
}
