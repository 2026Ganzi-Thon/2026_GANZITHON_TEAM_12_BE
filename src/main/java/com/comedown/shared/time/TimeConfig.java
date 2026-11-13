package com.comedown.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 애플리케이션 전체가 사용하는 {@link Clock}. 테스트에서는 test/support의 {@code @Primary} 고정 Clock으로 교체한다.
 * 코드에서 {@code LocalDate.now()}, {@code Instant.now()}를 직접 호출하지 말고 {@link ServiceTime}을 사용한다.
 * <p>
 * local 프로필은 {@code POST /api/dev/clock}으로 날짜를 옮길 수 있는 {@link AdjustableClock}을 쓰고,
 * 그 밖의 프로필은 실제 UTC 시계를 쓴다.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    @Profile("!local")
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile("local")
    public AdjustableClock adjustableClock() {
        return new AdjustableClock(Clock.systemUTC());
    }
}
