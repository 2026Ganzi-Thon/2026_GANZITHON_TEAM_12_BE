package com.comedown.recommendation.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrepareAheadPolicyTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final PrepareAheadPolicy policy = new PrepareAheadPolicy(Duration.ofMinutes(30), 2, SEOUL);

    @Test
    void 후보_날짜는_마감_전날부터_오늘까지_거슬러_올라간다() {
        LocalDate today = LocalDate.of(2026, 11, 12);

        assertThat(policy.candidateDays(today, LocalDate.of(2026, 11, 16)))
                .containsExactly(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 13),
                        LocalDate.of(2026, 11, 12));
    }

    @Test
    void 후보는_최대_7일_전까지이고_마감이_오늘_이전이면_없다() {
        LocalDate today = LocalDate.of(2026, 11, 1);

        List<LocalDate> days = policy.candidateDays(today, LocalDate.of(2026, 11, 20));
        assertThat(days).hasSize(7).first().isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(days).last().isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(policy.candidateDays(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12))).isEmpty();
        assertThat(policy.candidateDays(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 10))).isEmpty();
    }

    @Test
    void 문구는_위험_주의_마감_수와_마감을_옮기지_않는다는_점을_알린다() {
        LocalDate thisWeek = LocalDate.of(2026, 11, 9);

        assertThat(policy.message(thisWeek, LocalDate.of(2026, 11, 16), 3))
                .isEqualTo("다음 주에 마감 3건이 몰려 있어요. 과제를 30분만 미리 시작해 볼까요? (마감일은 그대로예요)");
        assertThat(policy.message(thisWeek, thisWeek, 2))
                .isEqualTo("이번 주에 마감 2건이 몰려 있어요. 과제를 30분만 미리 시작해 볼까요? (마감일은 그대로예요)");
        assertThat(policy.message(thisWeek, LocalDate.of(2026, 11, 30), 4))
                .startsWith("11월 30일 주에 마감 4건이 몰려 있어요.");
        // 마감이 1건 이하면 "몰려 있다"고 하지 않고 건수를 빼고 말한다.
        assertThat(policy.message(thisWeek, LocalDate.of(2026, 11, 16), 1))
                .isEqualTo("다음 주에 일정이 많이 몰려 있어요. 과제를 30분만 미리 시작해 볼까요? (마감일은 그대로예요)");
        assertThat(policy.message(thisWeek, LocalDate.of(2026, 11, 16), 0)).startsWith("다음 주에 일정이 많이 몰려 있어요.");
    }

    @Test
    void 제안은_그_날짜가_지나면_만료된다() {
        assertThat(policy.expiresAt(LocalDate.of(2026, 11, 17))).isEqualTo(Instant.parse("2026-11-17T15:00:00Z"));
    }

    @Test
    void 대상은_과제이고_위험_주만() {
        assertThat(policy.isTarget("ASSIGNMENT")).isTrue();
        assertThat(policy.isTarget("EXAM")).isFalse();
        assertThat(policy.isRiskWeek("RISK")).isTrue();
        assertThat(policy.isRiskWeek("WATCH")).isFalse();
    }

    @Test
    void 길이와_개수는_양수여야_한다() {
        assertThatThrownBy(() -> new PrepareAheadPolicy(Duration.ZERO, 2, SEOUL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrepareAheadPolicy(Duration.ofMinutes(30), 0, SEOUL))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
