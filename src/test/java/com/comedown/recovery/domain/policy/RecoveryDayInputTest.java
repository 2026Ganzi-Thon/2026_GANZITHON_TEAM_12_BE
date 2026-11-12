package com.comedown.recovery.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 계산 정책 4절 하루 대표값·적격일. */
class RecoveryDayInputTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    private static MeasurementSample sample(String isoTime, String quality, Double ln) {
        return new MeasurementSample(Instant.parse(isoTime), quality, ln);
    }

    @Test
    void 대표값은_측정_시작_순으로_첫_적격_측정이고_나중_값은_섞지_않는다() {
        List<MeasurementSample> measurements = List.of(
                sample("2026-11-12T11:00:00Z", "GOOD", 3.2),   // 저녁(서울 20시) 측정
                sample("2026-11-11T22:10:00Z", "FAIR", 3.7),   // 아침(서울 07:10) 첫 측정
                sample("2026-11-11T22:20:00Z", "GOOD", 3.9));

        RecoveryDayInput input = RecoveryDayInput.of(DAY, measurements, true, false);

        assertThat(input.dailyLn()).isEqualTo(3.7);
        assertThat(input.qualityLevel()).isEqualTo("FAIR");
        assertThat(input.measurementCount()).isEqualTo(3);
    }

    @Test
    void 첫_측정이_RETRY면_다음_적격_재측정이_대표값() {
        List<MeasurementSample> measurements = List.of(
                sample("2026-11-11T22:10:00Z", "RETRY", null),
                sample("2026-11-11T22:12:00Z", "GOOD", 3.8));

        RecoveryDayInput input = RecoveryDayInput.of(DAY, measurements, true, false);

        assertThat(input.dailyLn()).isEqualTo(3.8);
        assertThat(input.qualityLevel()).isEqualTo("GOOD");
        assertThat(input.measurementCount()).isEqualTo(1);
    }

    @Test
    void RETRY와_수동값_UNVERIFIED는_대표값이_되지_않는다() {
        List<MeasurementSample> measurements = List.of(
                sample("2026-11-11T22:10:00Z", "RETRY", null),
                sample("2026-11-11T22:15:00Z", "UNVERIFIED", 3.8));

        RecoveryDayInput input = RecoveryDayInput.of(DAY, measurements, true, false);

        assertThat(input.dailyLn()).isNull();
        assertThat(input.qualityLevel()).isNull();
        assertThat(input.measurementCount()).isZero();
        assertThat(input.eligible()).isFalse();
    }

    @Test
    void 적격일은_대표값_컨디션_입력_완료_교란_없음이_모두_필요() {
        assertThat(new RecoveryDayInput(DAY, 3.7, "GOOD", 1, true, false).eligible()).isTrue();
        // 컨디션 입력 전은 무교란으로 간주하지 않는다
        assertThat(new RecoveryDayInput(DAY, 3.7, "GOOD", 1, false, false).eligible()).isFalse();
        assertThat(new RecoveryDayInput(DAY, 3.7, "GOOD", 1, true, true).eligible()).isFalse();
        assertThat(new RecoveryDayInput(DAY, null, null, 0, true, false).eligible()).isFalse();
    }

    @Test
    void 대표값과_품질이_어긋난_입력은_거부() {
        assertThatThrownBy(() -> new RecoveryDayInput(DAY, 3.7, null, 1, true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoveryDayInput(DAY, 3.7, "RETRY", 1, true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoveryDayInput(DAY, 3.7, "GOOD", 0, true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoveryDayInput(DAY, Double.NaN, "GOOD", 1, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
