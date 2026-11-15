package com.comedown.experience.reports.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 리포트 그래프의 주 = 온보딩 기준 n주차(1주차 = 1~7일차). */
class ProgramWeeksTest {

    private static final LocalDate DAY1 = LocalDate.of(2026, 10, 16);

    @Test
    void 일차를_7일씩_묶어_주차를_센다() {
        assertThat(ProgramWeeks.weekOf(DAY1, DAY1)).isEqualTo(1);
        assertThat(ProgramWeeks.weekOf(DAY1, DAY1.plusDays(6))).isEqualTo(1);   // 7일차
        assertThat(ProgramWeeks.weekOf(DAY1, DAY1.plusDays(7))).isEqualTo(2);   // 8일차
        assertThat(ProgramWeeks.weekOf(DAY1, DAY1.plusDays(27))).isEqualTo(4);  // 28일차
        assertThat(ProgramWeeks.weekOf(DAY1, DAY1.plusDays(28))).isEqualTo(5);  // 29일차
        assertThat(ProgramWeeks.weekOf(DAY1, DAY1.minusDays(1))).isEqualTo(1);  // 온보딩 전 날짜는 1일차 규칙
    }

    @Test
    void 이십팔일차에_4주를_보면_1주차부터_4주차까지() {
        ProgramWeeks weeks = ProgramWeeks.recent(DAY1, DAY1.plusDays(27), 28);

        assertThat(weeks.fromWeek()).isEqualTo(1);
        assertThat(weeks.toWeek()).isEqualTo(4);
        assertThat(weeks.from()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(weeks.to()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(weeks.startOf(4)).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(weeks.endOf(4)).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    @Test
    void 삼십일차에_4주를_보면_진행_중인_5주차까지_막대_4개() {
        ProgramWeeks weeks = ProgramWeeks.recent(DAY1, DAY1.plusDays(29), 28);

        assertThat(weeks.fromWeek()).isEqualTo(2);
        assertThat(weeks.toWeek()).isEqualTo(5);
        assertThat(weeks.currentWeek()).isEqualTo(5);
        assertThat(weeks.from()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(weeks.to()).isEqualTo(LocalDate.of(2026, 11, 19)); // 오늘(11-14)보다 뒤
    }

    @Test
    void 일수는_7일_단위로_올림하고_1주차보다_앞으로_가지_않는다() {
        assertThat(ProgramWeeks.recent(DAY1, DAY1.plusDays(27), 10).fromWeek()).isEqualTo(3);
        assertThat(ProgramWeeks.recent(DAY1, DAY1.plusDays(27), 7).fromWeek()).isEqualTo(4);
        assertThat(ProgramWeeks.recent(DAY1, DAY1.plusDays(9), 90).fromWeek()).isEqualTo(1);
    }
}
