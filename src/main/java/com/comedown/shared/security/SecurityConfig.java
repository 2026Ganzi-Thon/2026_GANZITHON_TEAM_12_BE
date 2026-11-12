package com.comedown.shared.security;

import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ErrorResponseWriter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * 공통 보안 필터 체인.
 * <ul>
 *   <li>세션 쿠키 인증. {@code /api/**}는 로그인 필요({@code GET /api/auth/csrf} 제외) → 401 UNAUTHENTICATED</li>
 *   <li>CSRF: XSRF-TOKEN 쿠키 + X-XSRF-TOKEN 헤더(SPA 방식)</li>
 *   <li>{@code POST /api/auth/logout} → 204</li>
 *   <li>Google OAuth2 로그인 등 기능별 설정은 {@link SecurityFilterChainContributor} 빈으로 덧붙인다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ErrorResponseWriter errorWriter,
            List<SecurityFilterChainContributor> contributors) throws Exception {
        http
                // spa(): XSRF-TOKEN 쿠키(HttpOnly 아님) 저장소 + X-XSRF-TOKEN 헤더 검증
                .csrf(csrf -> csrf.spa())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                errorWriter.write(response, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, e) ->
                                errorWriter.write(response, e instanceof CsrfException
                                        ? ErrorCode.CSRF_TOKEN_INVALID
                                        : ErrorCode.FORBIDDEN)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("SESSION", "XSRF-TOKEN"));

        for (SecurityFilterChainContributor contributor : contributors) {
            contributor.contribute(http);
        }
        return http.build();
    }
}
