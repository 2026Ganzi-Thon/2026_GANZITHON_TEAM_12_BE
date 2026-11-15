package com.comedown.account.application;

import com.comedown.account.contract.AccountView;
import com.comedown.account.infrastructure.persistence.AccountStore;
import com.comedown.account.infrastructure.persistence.AccountStore.ConsentRow;
import com.comedown.account.infrastructure.persistence.AccountStore.SettingsRow;
import com.comedown.account.infrastructure.persistence.CalendarConnectionReader;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 정보·생활 정보·동의 조회. */
@Service
public class AccountQueryService {

    public record SettingsView(LocalTime measureTime, LocalTime sleepStart, LocalTime sleepEnd,
            List<String> preferredActivities, boolean hrvEnabled) {
    }

    public record ConsentView(String type, boolean agreed, Instant agreedAt, String version) {
    }

    private final AccountStore store;
    private final CalendarConnectionReader calendar;

    public AccountQueryService(AccountStore store, CalendarConnectionReader calendar) {
        this.store = store;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public AccountView me(long userId) {
        var user = store.findUser(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Map<String, ConsentRow> latest = store.latestConsents(userId);
        Map<String, Boolean> consents = new LinkedHashMap<>();
        for (String type : List.of("TERMS", "PRIVACY", "AGE_14", "SENSITIVE_HRV", "CALENDAR", "AI_NOTICE")) {
            ConsentRow row = latest.get(type);
            consents.put(type, row != null && row.active());
        }
        boolean hrv = store.findSettings(userId).map(SettingsRow::hrvEnabled).orElse(false);
        return new AccountView(user.id(), user.email(), user.nickname(), user.onboardingStep(), user.completedAt(),
                store.hasToken(userId), calendar.hasCalendarScope(userId), consents, hrv);
    }

    @Transactional(readOnly = true)
    public SettingsView settings(long userId) {
        SettingsRow row = store.findSettings(userId).orElse(null);
        return new SettingsView(row == null ? null : row.measureTime(), row == null ? null : row.sleepStart(),
                row == null ? null : row.sleepEnd(), store.preferredActivities(userId),
                row != null && row.hrvEnabled());
    }

    /** 기록이 있는 동의만(가장 최근 한 건씩). */
    @Transactional(readOnly = true)
    public List<ConsentView> consents(long userId) {
        List<ConsentView> list = new ArrayList<>();
        store.latestConsents(userId).values().forEach(r ->
                list.add(new ConsentView(r.type(), r.active(), r.agreedAt(), r.version())));
        return list;
    }
}
