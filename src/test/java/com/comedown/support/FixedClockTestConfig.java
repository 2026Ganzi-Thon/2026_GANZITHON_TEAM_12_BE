package com.comedown.support;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 테스트 컨텍스트의 Clock을 고정 시계로 교체한다. 기본 시각: 2026-11-12 07:30 (서울, 목요일).
 * <pre>{@code @Import(FixedClockTestConfig.class)}</pre>
 * 시각을 옮기려면 {@link MutableClock} 빈을 주입받아 {@code set(...)}/{@code advance(...)}를 호출한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockTestConfig {

    public static final String DEFAULT_NOW = "2026-11-12T07:30:00+09:00";

    /** {@link Clock} 타입으로 주입받는 코드도 @Primary인 이 고정 시계를 쓴다. */
    @Bean
    @Primary
    public MutableClock testClock() {
        return MutableClock.at(DEFAULT_NOW);
    }
}
