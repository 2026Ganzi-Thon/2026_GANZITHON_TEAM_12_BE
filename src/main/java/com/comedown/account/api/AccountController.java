package com.comedown.account.api;

import com.comedown.account.application.AccountQueryService;
import com.comedown.account.application.AccountService;
import com.comedown.account.contract.AccountView;
import com.comedown.checkin.contract.DayIndex;
import com.comedown.shared.security.CurrentUser;
import com.comedown.shared.time.ServiceTime;
import com.comedown.subscription.contract.PlanView;
import com.comedown.subscription.contract.SubscriptionQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 내 정보·생활 정보 조회·동의 API(HTTP 변환만). 로그인만 필요하고 온보딩 중에도 쓴다. */
@RestController
class AccountController {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final AccountQueryService query;
    private final AccountService service;
    private final SubscriptionQuery subscription;
    private final ServiceTime time;

    AccountController(AccountQueryService query, AccountService service, SubscriptionQuery subscription,
            ServiceTime time) {
        this.query = query;
        this.service = service;
        this.subscription = subscription;
        this.time = time;
    }

    @GetMapping("/api/me")
    MeResponse me(CurrentUser user) {
        AccountView a = query.me(user.id());
        Integer dayIndex = a.onboardingCompletedAt() == null ? null
                : DayIndex.of(time.toServiceDate(a.onboardingCompletedAt()), time.today());
        PlanView plan = subscription.find(user.id()).orElse(null);
        return new MeResponse(a.id(), a.email(), a.nickname(), a.onboardingStep(),
                time.toResponse(a.onboardingCompletedAt()), dayIndex,
                new Calendar(a.calendarConnected(), a.calendarScopeGranted()), a.consents(), a.hrvEnabled(),
                plan == null ? null : new Plan(plan.plan(), time.toResponse(plan.trialEndsAt()), plan.daysLeft()));
    }

    @PatchMapping("/api/me")
    NicknameResponse nickname(CurrentUser user, @Valid @RequestBody NicknameRequest body) {
        service.updateNickname(user.id(), body.nickname());
        return new NicknameResponse(query.me(user.id()).nickname());
    }

    @GetMapping("/api/me/settings")
    SettingsResponse settings(CurrentUser user) {
        AccountQueryService.SettingsView s = query.settings(user.id());
        return new SettingsResponse(format(s.measureTime()), format(s.sleepStart()), format(s.sleepEnd()),
                s.preferredActivities(), s.hrvEnabled());
    }

    @GetMapping("/api/me/consents")
    ConsentsResponse consents(CurrentUser user) {
        return new ConsentsResponse(query.consents(user.id()).stream()
                .map(c -> new ConsentItem(c.type(), c.agreed(), time.toResponse(c.agreedAt()), c.version()))
                .toList());
    }

    @PostMapping("/api/me/consents")
    @ResponseStatus(HttpStatus.OK)
    ConsentsResponse consent(CurrentUser user, @Valid @RequestBody ConsentRequest body) {
        service.recordConsent(user.id(), body.type(), body.version());
        return consents(user);
    }

    private static String format(LocalTime t) {
        return t == null ? null : t.format(HH_MM);
    }

    record Calendar(boolean connected, boolean scopeGranted) {
    }

    record Plan(String plan, OffsetDateTime trialEndsAt, int daysLeft) {
    }

    record MeResponse(long id, String email, String nickname, String onboardingStep,
            OffsetDateTime onboardingCompletedAt, Integer dayIndex, Calendar calendar, Map<String, Boolean> consents,
            boolean hrvEnabled, Plan plan) {
    }

    record NicknameRequest(@NotNull String nickname) {
    }

    record NicknameResponse(String nickname) {
    }

    record SettingsResponse(String measureTime, String sleepStart, String sleepEnd, List<String> preferredActivities,
            boolean hrvEnabled) {
    }

    record ConsentRequest(@NotNull String type, @NotNull String version) {
    }

    record ConsentItem(String type, boolean agreed, OffsetDateTime agreedAt, String version) {
    }

    record ConsentsResponse(List<ConsentItem> consents) {
    }
}
