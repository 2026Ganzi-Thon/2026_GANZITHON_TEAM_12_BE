package com.comedown.account.infrastructure.security;

import com.comedown.account.application.GoogleAccountService;
import com.comedown.account.application.GoogleAccountService.TokenGrant;
import com.comedown.shared.security.ComeDownPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * 로그인·캘린더 권한 허용 성공 처리. 구글이 준 토큰을 암호화해 저장하고 메모리의 인증 클라이언트는 지운다.
 * 로그인은 {@code /}, 캘린더 권한은 {@code /onboarding/cbi}로 돌려보낸다.
 * 토큰 저장에 실패해도 로그인은 유지하고(캘린더만 미연결로 남음) 토큰 값은 로그에 남기지 않는다.
 * <p>
 * 남아 있는 구글 철회 요청(연결 해제·탈퇴 재시도)은 캘린더 연결({@code google-calendar})만 풀 수 있다.
 * 일반 로그인({@code google})은 철회 대기 중인 토큰 행을 건드리지 않는다.
 */
class GoogleOAuthSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthSuccessHandler.class);

    private final OAuth2AuthorizedClientService clients;
    private final GoogleAccountService accounts;

    GoogleOAuthSuccessHandler(OAuth2AuthorizedClientService clients, GoogleAccountService accounts) {
        this.clients = clients;
        this.accounts = accounts;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String registrationId = token.getAuthorizedClientRegistrationId();
        OAuth2AuthorizedClient client = clients.loadAuthorizedClient(registrationId, token.getName());
        if (client != null) {
            try {
                saveTokens(token, client, GoogleOAuthConfig.CALENDAR_REGISTRATION.equals(registrationId));
            } catch (RuntimeException e) {
                log.warn("구글 토큰 저장에 실패했습니다(registration={}).", registrationId, e);
            } finally {
                clients.removeAuthorizedClient(registrationId, token.getName());
            }
        }
        response.sendRedirect(GoogleOAuthConfig.CALENDAR_REGISTRATION.equals(registrationId)
                ? "/onboarding/cbi" : "/");
    }

    private void saveTokens(OAuth2AuthenticationToken token, OAuth2AuthorizedClient client,
            boolean calendarConnection) {
        long userId = ((ComeDownPrincipal) token.getPrincipal()).userId();
        String sub = ((OidcUser) token.getPrincipal()).getSubject();
        OAuth2RefreshToken refresh = client.getRefreshToken();
        TokenGrant grant = new TokenGrant(
                client.getAccessToken().getTokenValue(),
                refresh == null ? null : refresh.getTokenValue(),
                client.getAccessToken().getIssuedAt(),
                client.getAccessToken().getExpiresAt(),
                client.getAccessToken().getScopes());
        if (calendarConnection) {
            accounts.saveCalendarTokens(userId, sub, grant);
        } else {
            accounts.saveLoginTokens(userId, sub, grant);
        }
    }
}
