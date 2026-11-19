package com.comedown.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 계산 정책 6절 내 범위 중 현재 위치(일정 부하, 온보딩 기준 n주차 점수). */
class LoadRangePolicyTest {

    /** 1~4주차 부하 10·20·30·40. 하위 5% = 11.5, 상위 5% = 38.5. */
    private static final List<Double> FOUR_WEEKS = List.of(40.0, 10.0, 30.0, 20.0);

    @Test
    void 주차_표본이_4개_미만이면_계산하지_않는다() {
        assertThat(LoadRangePolicy.evaluate(List.of(10.0, 20.0, 30.0), 30)).isEmpty();
    }

    @Test
    void 최저_최고는_5와_95_백분위_선형_보간이고_위치는_비율이다() {
        LoadRangePolicy.Position p = LoadRangePolicy.evaluate(FOUR_WEEKS, 25).orElseThrow();

        assertThat(p.low()).isCloseTo(11.5, within(1e-9));
        assertThat(p.high()).isCloseTo(38.5, within(1e-9));
        assertThat(p.position()).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void 위치는_0에서_100으로_제한한다() {
        assertThat(LoadRangePolicy.evaluate(FOUR_WEEKS, 40).orElseThrow().position()).isEqualTo(100);
        assertThat(LoadRangePolicy.evaluate(FOUR_WEEKS, 10).orElseThrow().position()).isZero();
    }

    @Test
    void 모든_주차_부하가_같으면_계산하지_않는다() {
        assertThat(LoadRangePolicy.evaluate(List.of(12.0, 12.0, 12.0, 12.0), 12)).isEmpty();
    }
}
