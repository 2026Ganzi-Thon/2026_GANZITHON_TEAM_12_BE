package com.comedown.shared.security.dev;

import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ErrorResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

/**
 * local 프로필 전용 {@code /api/dev/**} 보안 체인. 테스트 로그인만 로그인 없이 허용하고 나머지 개발 API는 로그인이 필요하다.
 * CSRF·오류 응답은 공통 체인과 같다. local이 아니면 이 체인과 개발 Controller가 모두 없으므로
 * {@code /api/dev/**}는 공통 체인에서 401(비로그인) 또는 404가 된다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class DevSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http, ErrorResponseWriter errorWriter)
            throws Exception {
        http
                .securityMatcher("/api/dev/**")
                .csrf(csrf -> csrf.spa())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/dev/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                errorWriter.write(response, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, e) ->
                                errorWriter.write(response, e instanceof CsrfException
                                        ? ErrorCode.CSRF_TOKEN_INVALID
                                        : ErrorCode.FORBIDDEN)));
        return http.build();
    }
}
