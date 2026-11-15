package com.comedown.shared.config;

import com.comedown.shared.error.ErrorResponseWriter;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 요청 본문 크기 제한 필터 등록. 보안 필터보다 먼저 실행해 큰 본문을 읽기 전에 거절한다.
 */
@Configuration(proxyBeanMethods = false)
public class RequestBodyLimitConfig {

    /** Spring Session 필터(HIGHEST_PRECEDENCE + 50) 다음, Spring Security(-100)보다 앞. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    @Bean
    public FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(
            ObjectProvider<RequestBodyLimit> limits, ErrorResponseWriter errorWriter) {
        List<RequestBodyLimit> registered = limits.orderedStream().toList();
        FilterRegistrationBean<RequestBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestBodyLimitFilter(registered, errorWriter));
        registration.setOrder(ORDER);
        return registration;
    }
}
