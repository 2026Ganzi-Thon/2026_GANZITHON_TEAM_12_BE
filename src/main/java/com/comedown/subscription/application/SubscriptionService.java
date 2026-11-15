package com.comedown.subscription.application;

import com.comedown.shared.time.ServiceTime;
import com.comedown.subscription.contract.PlanView;
import com.comedown.subscription.contract.SubscriptionCommands;
import com.comedown.subscription.contract.SubscriptionQuery;
import com.comedown.subscription.infrastructure.persistence.SubscriptionStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 무료 체험 시작·이용 자격 조회. 체험 기간은 시작 + 28일이다(결제 연동 전, 구독은 아직 없다). */
@Service
public class SubscriptionService implements SubscriptionQuery, SubscriptionCommands {

    static final int TRIAL_DAYS = 28;
    static final String TRIAL = "TRIAL";
    static final String FREE = "FREE";

    private final SubscriptionStore store;
    private final ServiceTime time;

    public SubscriptionService(SubscriptionStore store, ServiceTime time) {
        this.store = store;
        this.time = time;
    }

    @Override
    @Transactional
    public void startTrial(long userId, Instant startedAt) {
        store.insertIfAbsent(userId, TRIAL, startedAt, startedAt.plus(TRIAL_DAYS, ChronoUnit.DAYS), time.now());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanView> find(long userId) {
        return store.find(userId).map(row -> {
            boolean trialing = TRIAL.equals(row.plan()) && time.now().isBefore(row.trialEndsAt());
            String plan = TRIAL.equals(row.plan()) && !trialing ? FREE : row.plan();
            long days = ChronoUnit.DAYS.between(time.today(), time.toServiceDate(row.trialEndsAt()));
            return new PlanView(plan, row.trialEndsAt(), trialing ? (int) Math.max(0, days) : 0);
        });
    }
}
