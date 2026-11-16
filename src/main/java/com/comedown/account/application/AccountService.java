package com.comedown.account.application;

import com.comedown.account.contract.AccountCommands;
import com.comedown.account.infrastructure.persistence.AccountStore;
import com.comedown.account.infrastructure.persistence.OnboardingStepWriter;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ErrorResponse.FieldError;
import com.comedown.shared.error.FieldValidationException;
import com.comedown.shared.time.ServiceTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로필·동의·생활 정보 쓰기. 온보딩 단계는 서버가 전진시키고 지난 단계는 되돌리지 않는다. */
@Service
public class AccountService implements AccountCommands {

    static final String SENSITIVE_HRV = "SENSITIVE_HRV";
    static final Set<String> CONSENT_TYPES =
            Set.of("TERMS", "PRIVACY", "AGE_14", "SENSITIVE_HRV", "CALENDAR", "AI_NOTICE");
    /** 가입 때 6개 모두 필수(2026-11-17 결정: 건강정보 동의도 필수). 건강정보 동의는 가입 뒤 철회할 수 있다. */
    static final List<String> REQUIRED_CONSENTS =
            List.of("TERMS", "PRIVACY", "AGE_14", "SENSITIVE_HRV", "CALENDAR", "AI_NOTICE");
    static final Set<String> ACTIVITY_CODES = Set.of("WALK", "STRETCH", "NAP", "YOGA", "MEDITATION", "LIGHT_RUN");
    static final int NICKNAME_MAX = 20;

    private final AccountStore store;
    private final OnboardingStepWriter steps;
    private final ServiceTime time;

    public AccountService(AccountStore store, OnboardingStepWriter steps, ServiceTime time) {
        this.store = store;
        this.steps = steps;
        this.time = time;
    }

    @Override
    @Transactional
    public String saveProfile(long userId, Profile profile) {
        List<FieldError> errors = new ArrayList<>();
        String nickname = validNickname(profile.nickname(), errors);
        for (String type : REQUIRED_CONSENTS) {
            if (!Boolean.TRUE.equals(profile.consents() == null ? null : profile.consents().get(type))) {
                errors.add(new FieldError("consents." + type, "필수 동의예요."));
            }
        }
        if (profile.consentVersion() == null || profile.consentVersion().isBlank()
                || profile.consentVersion().length() > 20) {
            errors.add(new FieldError("consentVersion", "동의 문서 버전이 필요해요."));
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        store.updateNickname(userId, nickname);
        for (String type : CONSENT_TYPES) {
            if (Boolean.TRUE.equals(profile.consents().get(type))) {
                recordConsent(userId, type, profile.consentVersion());
            }
        }
        steps.advance(userId, "PROFILE", "CBI");
        return steps.currentStep(userId);
    }

    @Override
    @Transactional
    public String replaceSettings(long userId, Settings settings) {
        List<FieldError> errors = new ArrayList<>();
        if (settings.measureTime() == null) {
            errors.add(new FieldError("measureTime", "측정 시간이 필요해요."));
        }
        if (settings.sleepStart() == null) {
            errors.add(new FieldError("sleepStart", "수면 시작 시간이 필요해요."));
        }
        if (settings.sleepEnd() == null) {
            errors.add(new FieldError("sleepEnd", "수면 종료 시간이 필요해요."));
        }
        if (settings.sleepStart() != null && settings.sleepStart().equals(settings.sleepEnd())) {
            errors.add(new FieldError("sleepEnd", "수면 시작과 종료 시간이 같을 수 없어요."));
        }
        List<String> activities = settings.preferredActivities() == null ? List.of() : settings.preferredActivities();
        if (activities.isEmpty()) {
            errors.add(new FieldError("preferredActivities", "활동을 1개 이상 골라 주세요."));
        } else if (new HashSet<>(activities).size() != activities.size()) {
            errors.add(new FieldError("preferredActivities", "중복된 활동이 있어요."));
        } else if (!ACTIVITY_CODES.containsAll(activities)) {
            errors.add(new FieldError("preferredActivities", "알 수 없는 활동이 있어요."));
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        String step = steps.currentStep(userId);
        if ("PROFILE".equals(step) || "CBI".equals(step)) {
            throw new ApiException(ErrorCode.ONBOARDING_STEP_INVALID);
        }
        store.upsertLifestyle(userId, settings.measureTime(), settings.sleepStart(), settings.sleepEnd(), time.now());
        store.replaceActivities(userId, activities);
        steps.advance(userId, "LIFESTYLE", "SCHEDULE_REPORT");
        return steps.currentStep(userId);
    }

    /** 닉네임 수정(마이페이지). */
    @Transactional
    public void updateNickname(long userId, String nickname) {
        List<FieldError> errors = new ArrayList<>();
        String valid = validNickname(nickname, errors);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        store.updateNickname(userId, valid);
    }

    /**
     * 동의를 기록한다. 이미 유효한 동의가 있으면 그대로 두고, SENSITIVE_HRV는 측정을 켠다.
     * 알 수 없는 유형은 400.
     */
    @Transactional
    public void recordConsent(long userId, String type, String version) {
        if (type == null || !CONSENT_TYPES.contains(type)) {
            throw FieldValidationException.of("type", "알 수 없는 동의 유형이에요.");
        }
        if (version == null || version.isBlank() || version.length() > 20) {
            throw FieldValidationException.of("version", "동의 문서 버전이 필요해요.");
        }
        var latest = store.latestConsents(userId).get(type);
        if (latest == null || !latest.active()) {
            store.insertConsent(userId, type, version, time.now());
        }
        if (SENSITIVE_HRV.equals(type)) {
            store.setHrvEnabled(userId, true, time.now());
        }
    }

    @Override
    @Transactional
    public void withdrawHealthConsent(long userId) {
        store.withdrawLatestConsent(userId, SENSITIVE_HRV, time.now());
        store.setHrvEnabled(userId, false, time.now());
    }

    private static String validNickname(String raw, List<FieldError> errors) {
        String nickname = raw == null ? "" : raw.trim();
        int length = nickname.codePointCount(0, nickname.length());
        if (length < 1 || length > NICKNAME_MAX) {
            errors.add(new FieldError("nickname", "1~20자여야 합니다."));
        }
        return nickname;
    }
}
