package com.comedown.safety.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.comedown.safety.contract.RecoveryDayStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 계산 정책 5절 2주 연속 낮음. 오늘 = 2026-11-14, 창 = 11-01 ~ 11-14. */
class LowRecoveryRuleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    private static RecoveryDayStatus low(int daysAgo) {
        return new RecoveryDayStatus(TODAY.minusDays(daysAgo), true, "LOW");
    }

    private static List<RecoveryDayStatus> lowDays(int... daysAgo) {
        List<RecoveryDayStatus> days = new ArrayList<>();
        for (int d : daysAgo) {
            days.add(low(d));
        }
        return days;
    }

    @Test
    void 대표값_있는_날이_7일_이상이고_모두_LOW면_충족하고_시작일을_돌려준다() {
        // 측정하지 않은 날(사이사이 빈 날)은 판정에서 빠진다
        List<RecoveryDayStatus> days = lowDays(13, 11, 9, 7, 5, 3, 1);

        assertThat(LowRecoveryRule.evaluate(days, TODAY)).contains(TODAY.minusDays(13));
    }

    @Test
    void 대표값_있는_날이_6일이면_충족하지_않는다() {
        assertThat(LowRecoveryRule.evaluate(lowDays(6, 5, 4, 3, 2, 1), TODAY)).isEmpty();
    }

    @Test
    void 대표값_있는_날_중_하나라도_LOW가_아니면_충족하지_않는다() {
        List<RecoveryDayStatus> days = lowDays(7, 6, 5, 4, 3, 2, 1);
        days.add(new RecoveryDayStatus(TODAY, true, "NORMAL"));

        assertThat(LowRecoveryRule.evaluate(days, TODAY)).isEmpty();
    }

    @Test
    void 대표값_없는_날은_상태와_관계없이_빠진다() {
        List<RecoveryDayStatus> days = lowDays(7, 6, 5, 4, 3, 2, 1);
        days.add(new RecoveryDayStatus(TODAY, false, "INSUFFICIENT"));

        assertThat(LowRecoveryRule.evaluate(days, TODAY)).contains(TODAY.minusDays(7));
    }

    @Test
    void 십사일_창_밖의_날은_세지_않는다() {
        // 14일 전(창 밖) LOW는 세지 않으므로 6일만 남는다
        assertThat(LowRecoveryRule.evaluate(lowDays(14, 6, 5, 4, 3, 2, 1), TODAY)).isEmpty();
    }
}
