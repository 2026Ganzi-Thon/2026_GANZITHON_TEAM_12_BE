package com.comedown.recovery.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 계산 정책 6절 내 범위 중 현재 위치(회복). */
class RangePositionPolicyTest {

    /** 1.0 ~ 20.0 (20개). 하위 5% = 1.95, 상위 5% = 19.05. */
    private static List<Double> oneToTwenty() {
        return IntStream.rangeClosed(1, 20).mapToObj(i -> (double) i).toList();
    }

    @Test
    void 추세_값이_20개_미만이면_계산하지_않는다() {
        assertThat(RangePositionPolicy.evaluate(oneToTwenty().subList(0, 19), 10)).isEmpty();
    }

    @Test
    void 최저_최고는_5와_95_백분위_선형_보간이고_위치는_비율이다() {
        RangePositionPolicy.Position p = RangePositionPolicy.evaluate(oneToTwenty(), 10.5).orElseThrow();

        assertThat(p.lowLn()).isCloseTo(1.95, within(1e-9));
        assertThat(p.highLn()).isCloseTo(19.05, within(1e-9));
        assertThat(p.position()).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void 위치는_0에서_100으로_제한한다() {
        assertThat(RangePositionPolicy.evaluate(oneToTwenty(), 20).orElseThrow().position()).isEqualTo(100);
        assertThat(RangePositionPolicy.evaluate(oneToTwenty(), 1).orElseThrow().position()).isZero();
    }

    @Test
    void 최저와_최고가_같으면_계산하지_않는다() {
        assertThat(RangePositionPolicy.evaluate(Collections.nCopies(25, 3.7), 3.7)).isEmpty();
    }
}
