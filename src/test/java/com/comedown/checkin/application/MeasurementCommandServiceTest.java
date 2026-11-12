package com.comedown.checkin.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MeasurementCommandServiceTest {

    private static final LocalDate ONBOARDED = LocalDate.of(2026, 11, 10);

    @Test
    void 온보딩_완료일이_1일차() {
        assertThat(MeasurementCommandService.dayIndex(ONBOARDED, ONBOARDED)).isEqualTo(1);
        assertThat(MeasurementCommandService.dayIndex(ONBOARDED, LocalDate.of(2026, 11, 12))).isEqualTo(3);
        assertThat(MeasurementCommandService.dayIndex(ONBOARDED, LocalDate.of(2026, 12, 7))).isEqualTo(28);
    }

    @Test
    void 완료일_이전_날짜의_측정은_1일차로_둔다() {
        assertThat(MeasurementCommandService.dayIndex(ONBOARDED, LocalDate.of(2026, 11, 9))).isEqualTo(1);
    }
}
