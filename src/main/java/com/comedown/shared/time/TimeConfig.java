package com.comedown.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 애플리케이션 전체가 사용하는 {@link Clock}. 테스트에서는 test/support의 {@code @Primary} 고정 Clock으로 교체한다.
 * 코드에서 {@code LocalDate.now()}, {@code Instant.now()}를 직접 호출하지 말고 {@link ServiceTime}을 사용한다.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
