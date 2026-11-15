package com.comedown.experience.onboarding.application;

import com.comedown.account.contract.AccountCommands;
import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.account.contract.OnboardingCommands;
import com.comedown.checkin.contract.DayIndex;
import com.comedown.shared.time.ServiceTime;
import com.comedown.subscription.contract.SubscriptionCommands;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 온보딩 흐름 조합: 프로필 → CBI 건너뛰기 → 생활 정보 → 완료(체험 시작). 단계 값은 account만 쓴다. */
@Service
public class OnboardingFlow {

    static final String CALENDAR_AUTH_URL = "/oauth2/authorization/google-calendar";

    public record ProfileResult(String onboardingStep, String calendarAuthUrl) {
    }

    public record CompleteResult(String onboardingStep, int dayIndex) {
    }

    private final AccountCommands account;
    private final OnboardingCommands onboarding;
    private final CalendarConnectionQuery calendar;
    private final SubscriptionCommands subscription;
    private final ServiceTime time;

    public OnboardingFlow(AccountCommands account, OnboardingCommands onboarding, CalendarConnectionQuery calendar,
            SubscriptionCommands subscription, ServiceTime time) {
        this.account = account;
        this.onboarding = onboarding;
        this.calendar = calendar;
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

    /** 온보딩을 끝내고 무료 체험을 시작한다(둘이 함께 성공하거나 함께 실패한다). */
    @Transactional
    public CompleteResult complete(long userId) {
        Instant completedAt = onboarding.completeOnboarding(userId);
        subscription.startTrial(userId, completedAt);
        return new CompleteResult("DONE", DayIndex.of(time.toServiceDate(completedAt), time.today()));
    }
}
