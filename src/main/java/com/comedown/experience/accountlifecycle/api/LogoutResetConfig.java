package com.comedown.experience.accountlifecycle.api;

import com.comedown.experience.accountlifecycle.application.LogoutReset;
import com.comedown.shared.security.ComeDownPrincipal;
import com.comedown.shared.security.SecurityFilterChainContributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시연 모드: 로그아웃({@code POST /api/auth/logout})하면 그 사용자를 가입 전 상태로 되돌린다({@link LogoutReset}).
 * {@code comedown.demo.reset-on-logout=true}일 때만 켜진다(기본 꺼짐, 운영 Cloud Run 서비스 정의에서 켬).
 * <p>
 * 로그아웃 처리기는 세션·인증 정보를 지우는 기본 처리기보다 먼저 돌므로 여기서 사용자 id를 읽을 수 있다.
 * 초기화가 실패해도 로그아웃은 그대로 끝난다(204).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "comedown.demo.reset-on-logout", havingValue = "true")
class LogoutResetConfig {

    private static final Logger log = LoggerFactory.getLogger(LogoutResetConfig.class);

    @Bean
    SecurityFilterChainContributor resetOnLogout(LogoutReset reset) {
        return http -> http.logout(logout -> logout.addLogoutHandler((request, response, authentication) -> {
            if (authentication != null && authentication.getPrincipal() instanceof ComeDownPrincipal principal) {
                try {
                    reset.reset(principal.userId());
                } catch (RuntimeException e) {
                    log.warn("로그아웃 초기화에 실패했습니다(userId={}, {})", principal.userId(),
                            e.getClass().getSimpleName());
                }
            }
        }));
    }
}
