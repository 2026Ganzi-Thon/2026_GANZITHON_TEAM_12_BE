package com.comedown.experience.morning;

import com.comedown.shared.config.RequestBodyLimit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 아침 체크 API의 HTTP 설정.
 */
@Configuration(proxyBeanMethods = false)
public class MorningWebConfig {

    /** 측정 저장 요청 본문 상한 512KB(60fps 70초 기준 약 100KB). */
    static final long MEASUREMENT_BODY_LIMIT_BYTES = 512L * 1024;

    @Bean
    public RequestBodyLimit measurementBodyLimit() {
        return new RequestBodyLimit("/api/hrv/measurements", MEASUREMENT_BODY_LIMIT_BYTES);
    }
}
