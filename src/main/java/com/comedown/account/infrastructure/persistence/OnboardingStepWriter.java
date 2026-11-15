package com.comedown.account.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * users.onboarding_step 전진(조건부 갱신). 지정한 이전 단계일 때만 바꾸므로 동시 요청·재시도에도 단계를 되돌리지 않는다.
 */
@Repository
public class OnboardingStepWriter {

    private final JdbcClient jdbc;

    public OnboardingStepWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 바뀐 행 수(0 또는 1) */
    public int advance(long userId, String from, String to) {
        return jdbc.sql("UPDATE users SET onboarding_step = ? WHERE id = ? AND onboarding_step = ?")
                .params(to, userId, from)
                .update();
    }

    public String currentStep(long userId) {
        return jdbc.sql("SELECT onboarding_step FROM users WHERE id = ?")
                .params(userId)
                .query(String.class)
                .single();
    }

    /** SCHEDULE_REPORT일 때만 DONE으로 바꾸고 완료 시각(UTC)을 기록한다. @return 바뀐 행 수 */
    public int complete(long userId, java.time.Instant completedAt) {
        return jdbc.sql("UPDATE users SET onboarding_step = 'DONE', onboarding_completed_at = ? "
                        + "WHERE id = ? AND onboarding_step = 'SCHEDULE_REPORT'")
                .params(java.time.LocalDateTime.ofInstant(completedAt, java.time.ZoneOffset.UTC), userId)
                .update();
    }
}
