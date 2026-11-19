package com.comedown.account.infrastructure.security;

import com.comedown.account.application.GoogleAccountService;
import com.comedown.account.application.GoogleAccountService.TokenGrant;
import com.comedown.account.contract.CalendarConnectionQuery;
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
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

/**
 * 로그인·캘린더 권한 허용 성공 처리. 구글이 준 토큰을 암호화해 저장하고 메모리의 인증 클라이언트는 지운다.
 * 로그인·캘린더 권한 모두 {@code /}로 돌려보내고, 프론트는 {@code GET /api/me}의 {@code onboardingStep}으로 화면을
 * 정한다. 그래서 가입은 구글 로그인 → 캘린더 권한 → 닉네임·동의(PROFILE) → CBI 순서가 된다(2026-11-19 결정).
 * 토큰 값은 로그에 남기지 않는다.
 * <p>
 * <b>캘린더 권한이 없으면 서비스에 들어올 수 없다</b>(2026-11-19 결정). 토큰을 저장한 뒤에도 캘린더가 연결되지 않았으면
 * 방금 만든 로그인 세션을 끊는다. 일반 로그인이면 캘린더 권한 화면({@code /oauth2/authorization/google-calendar},
 * 로그인하지 않은 상태라 로그인 + 연결로 동작)으로 보내고, 캘린더 권한 화면에서 캘린더를 허용하지 않았으면
 * {@code /?authError=true}로 보낸다. 구글이 캘린더 권한을 막은 계정(동의 화면 테스트 사용자가 아님)은 그 화면에서
 * 실패해 세션을 얻지 못한다. 토큰 저장에 실패해도 연결되지 않은 것으로 보고 같은 방식으로 끊는다.
 * <p>
 * 남아 있는 구글 철회 요청(연결 해제·탈퇴 재시도)은 캘린더 연결({@code google-calendar})만 풀 수 있다.
 * 일반 로그인({@code google})은 철회 대기 중인 토큰 행을 건드리지 않는다(그래서 그 사용자는 캘린더 권한 화면으로 간다).
 */
class GoogleOAuthSuccessHandler implements AuthenticationSuccessHandler {

    static final String CALENDAR_START = "/oauth2/authorization/" + GoogleOAuthConfig.CALENDAR_REGISTRATION;
    static final String AUTH_ERROR = "/?authError=true";
    static final String HOME = "/";
    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthSuccessHandler.class);

    private final OAuth2AuthorizedClientService clients;
    private final GoogleAccountService accounts;
    private final CalendarConnectionQuery calendar;
    private final SecurityContextLogoutHandler endSession = new SecurityContextLogoutHandler();

    GoogleOAuthSuccessHandler(OAuth2AuthorizedClientService clients, GoogleAccountService accounts,
            CalendarConnectionQuery calendar) {
        this.clients = clients;
        this.accounts = accounts;
        this.calendar = calendar;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String registrationId = token.getAuthorizedClientRegistrationId();
        boolean calendarConnection = GoogleOAuthConfig.CALENDAR_REGISTRATION.equals(registrationId);
        OAuth2AuthorizedClient client = clients.loadAuthorizedClient(registrationId, token.getName());
        if (client != null) {
            try {
                saveTokens(token, client, calendarConnection);
            } catch (RuntimeException e) {
                log.warn("구글 토큰 저장에 실패했습니다(registration={}).", registrationId, e);
            } finally {
                clients.removeAuthorizedClient(registrationId, token.getName());
            }
        }
        long userId = ((ComeDownPrincipal) token.getPrincipal()).userId();
        if (!calendar.isConnected(userId)) {
            // 세션(과 그 안의 인증 정보)을 지워 캘린더 권한 없이 API를 쓰지 못하게 한다.
            endSession.logout(request, response, authentication);
            response.sendRedirect(calendarConnection ? AUTH_ERROR : CALENDAR_START);
            return;
        }
        response.sendRedirect(HOME);
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
