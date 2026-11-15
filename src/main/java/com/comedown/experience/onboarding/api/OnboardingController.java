package com.comedown.experience.onboarding.api;

import com.comedown.account.contract.AccountCommands;
import com.comedown.experience.onboarding.application.OnboardingFlow;
import com.comedown.shared.error.ErrorResponse.FieldError;
import com.comedown.shared.error.FieldValidationException;
import com.comedown.shared.security.CurrentUser;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 온보딩 API(HTTP 변환·검증만): profile, cbi/skip, 생활 정보, complete. 로그인만 필요하고
 * 단계 선행 조건은 account가 검증한다.
 */
@RestController
class OnboardingController {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final OnboardingFlow flow;

    OnboardingController(OnboardingFlow flow) {
        this.flow = flow;
    }

    @PutMapping("/api/onboarding/profile")
    ProfileResponse profile(CurrentUser user, @Valid @RequestBody ProfileRequest body) {
        OnboardingFlow.ProfileResult r = flow.saveProfile(user.id(),
                new AccountCommands.Profile(body.nickname(), body.consents(), body.consentVersion()));
        return new ProfileResponse(r.onboardingStep(), r.calendarAuthUrl());
    }

    @PostMapping("/api/onboarding/cbi/skip")
    StepResponse skipCbi(CurrentUser user) {
        return new StepResponse(flow.skipCbi(user.id()));
    }

    @PutMapping("/api/me/settings")
    SettingsResponse settings(CurrentUser user, @Valid @RequestBody SettingsRequest body) {
        List<FieldError> errors = new ArrayList<>();
        LocalTime measure = parse("measureTime", body.measureTime(), errors);
        LocalTime sleepStart = parse("sleepStart", body.sleepStart(), errors);
        LocalTime sleepEnd = parse("sleepEnd", body.sleepEnd(), errors);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        String step = flow.saveSettings(user.id(),
                new AccountCommands.Settings(measure, sleepStart, sleepEnd, body.preferredActivities()));
        return new SettingsResponse(body.measureTime(), body.sleepStart(), body.sleepEnd(),
                body.preferredActivities(), "DONE".equals(step) ? null : step);
    }

    @PostMapping("/api/onboarding/complete")
    OnboardingFlow.CompleteResult complete(CurrentUser user) {
        return flow.complete(user.id());
    }

    private static LocalTime parse(String field, String value, List<FieldError> errors) {
        try {
            return LocalTime.parse(value, HH_MM);
        } catch (DateTimeParseException e) {
            errors.add(new FieldError(field, "HH:mm 형식이어야 합니다."));
            return null;
        }
    }

    record ProfileRequest(@NotNull String nickname, @NotNull Map<String, Boolean> consents,
            @NotNull String consentVersion) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProfileResponse(String onboardingStep, String calendarAuthUrl) {
    }

    record StepResponse(String onboardingStep) {
    }

    record SettingsRequest(@NotNull String measureTime, @NotNull String sleepStart, @NotNull String sleepEnd,
            @NotNull List<String> preferredActivities) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SettingsResponse(String measureTime, String sleepStart, String sleepEnd,
            List<String> preferredActivities, String onboardingStep) {
    }
}
