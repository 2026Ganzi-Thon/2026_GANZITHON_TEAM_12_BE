package com.comedown.experience.suggestions.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.comedown.recommendation.contract.SuggestionPayload;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SuggestionMessageAiTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18); // 수요일

    private static SuggestionPayload preStart(OffsetDateTime start) {
        return new SuggestionPayload(1L, "PRE_START", "PROPOSED", start.toLocalDate(), start, start.plusMinutes(30), 30,
                "과제 미리 시작하기", null, "RISK_WEEK", null,
                new SuggestionPayload.Message("다음 주에 마감 6건이 몰려 있어요.", false, true), null, null, null, null);
    }

    @Test
    void 며칠_뒤_제안은_오늘이라고_쓸_수_있는_말을_주지_않고_날짜와_요일을_준다() {
        var facts = SuggestionMessageAi.facts(preStart(OffsetDateTime.parse("2026-11-24T07:00:00+09:00")), TODAY);

        assertThat(facts).contains("제안 날짜: 11월 24일 화요일", "제안 시각: 오전 7시")
                .noneMatch(f -> f.contains("오늘") || f.contains("내일"));
    }

    @Test
    void 오늘_내일_제안은_그_말과_날짜를_함께_준다() {
        assertThat(SuggestionMessageAi.dayLabel(TODAY, TODAY)).isEqualTo("오늘(11월 18일 수요일)");
        assertThat(SuggestionMessageAi.dayLabel(TODAY.plusDays(1), TODAY)).isEqualTo("내일(11월 19일 목요일)");
        assertThat(SuggestionMessageAi.dayLabel(TODAY.plusDays(2), TODAY)).isEqualTo("11월 20일 금요일");
    }

    @Test
    void 날짜는_서울_시각으로_정한다() {
        // UTC로 11-23 22:30 = 서울 11-24 07:30
        var facts = SuggestionMessageAi.facts(
                preStart(OffsetDateTime.of(2026, 11, 23, 22, 30, 0, 0, ZoneOffset.UTC)), TODAY);

        assertThat(facts).contains("제안 날짜: 11월 24일 화요일", "제안 시각: 오전 7시 30분");
    }

    @Test
    void 시각은_오전_오후와_12시간제로_쓴다() {
        assertThat(SuggestionMessageAi.timeLabel(LocalTime.of(0, 0))).isEqualTo("오전 12시");
        assertThat(SuggestionMessageAi.timeLabel(LocalTime.of(9, 0))).isEqualTo("오전 9시");
        assertThat(SuggestionMessageAi.timeLabel(LocalTime.of(12, 30))).isEqualTo("오후 12시 30분");
        assertThat(SuggestionMessageAi.timeLabel(LocalTime.of(20, 30))).isEqualTo("오후 8시 30분");
    }
}
