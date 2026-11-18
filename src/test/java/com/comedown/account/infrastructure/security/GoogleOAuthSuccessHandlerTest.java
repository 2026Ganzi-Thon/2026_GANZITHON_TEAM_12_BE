package com.comedown.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.comedown.account.application.GoogleAccountService;
import com.comedown.account.application.GoogleAccountService.TokenGrant;
import com.comedown.account.contract.CalendarConnectionQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

class GoogleOAuthSuccessHandlerTest {

    private static final Instant ISSUED = Instant.parse("2026-11-11T22:30:00Z");

    private final OAuth2AuthorizedClientService clients = mock(OAuth2AuthorizedClientService.class);
    private final GoogleAccountService accounts = mock(GoogleAccountService.class);
    private final CalendarConnectionQuery calendar = mock(CalendarConnectionQuery.class);
    private final GoogleOAuthSuccessHandler handler = new GoogleOAuthSuccessHandler(clients, accounts, calendar);

    @org.junit.jupiter.api.BeforeEach
    void connected() {
        when(calendar.isConnected(42L)).thenReturn(true);
    }

    private OAuth2AuthenticationToken authentication(String registrationId) {
        OidcIdToken idToken = new OidcIdToken("t", ISSUED, ISSUED.plusSeconds(60), Map.of("sub", "google-sub"));
        ComeDownOidcUser user = new ComeDownOidcUser(42, List.of(new SimpleGrantedAuthority("OIDC_USER")),
                idToken, null);
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), registrationId);
    }

    private OAuth2AuthorizedClient client(String registrationId, String refresh, String... scopes) {
        ClientRegistration registration = GoogleOAuthConfig.registration(registrationId, "id", "secret", scopes);
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-value",
                ISSUED, ISSUED.plusSeconds(3600), Set.of(scopes));
        OAuth2RefreshToken refreshToken = refresh == null ? null : new OAuth2RefreshToken(refresh, ISSUED);
        return new OAuth2AuthorizedClient(registration, "42", access, refreshToken);
    }

    @Test
    void 로그인_성공은_로그인_토큰으로_저장하고_메모리_클라이언트를_지우고_루트로_보낸다() throws Exception {
        when(clients.loadAuthorizedClient("google", "42")).thenReturn(client("google", null, "openid", "email"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication("google"));

        ArgumentCaptor<TokenGrant> grant = ArgumentCaptor.forClass(TokenGrant.class);
        // 일반 로그인은 철회 요청을 풀지 않는 저장만 쓴다.
        verify(accounts).saveLoginTokens(eq(42L), eq("google-sub"), grant.capture());
        verify(accounts, never()).saveCalendarTokens(any(Long.class), any(), any());
        assertThat(grant.getValue().accessToken()).isEqualTo("access-value");
        assertThat(grant.getValue().refreshToken()).isNull();
        assertThat(grant.getValue().scopes()).containsExactlyInAnyOrder("openid", "email");
        verify(clients).removeAuthorizedClient("google", "42");
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void 로그인_응답에_캘린더_범위가_섞여_와도_캘린더_연결_저장을_쓰지_않는다() throws Exception {
        // include_granted_scopes 때문에 이전에 허용한 캘린더 범위가 로그인 토큰에 함께 온다.
        when(clients.loadAuthorizedClient("google", "42"))
                .thenReturn(client("google", null, "openid", "email", GoogleOAuthConfig.CALENDAR_SCOPE));

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), new MockHttpServletResponse(),
                authentication("google"));

        verify(accounts).saveLoginTokens(eq(42L), eq("google-sub"), any());
        verify(accounts, never()).saveCalendarTokens(any(Long.class), any(), any());
    }

    @Test
    void 캘린더_연결_성공은_refresh_token까지_저장하고_온보딩_CBI로_보낸다() throws Exception {
        String calendar = GoogleOAuthConfig.CALENDAR_SCOPE;
        when(clients.loadAuthorizedClient("google-calendar", "42"))
                .thenReturn(client("google-calendar", "refresh-value", "openid", "email", calendar));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication("google-calendar"));

        ArgumentCaptor<TokenGrant> grant = ArgumentCaptor.forClass(TokenGrant.class);
        verify(accounts).saveCalendarTokens(eq(42L), eq("google-sub"), grant.capture());
        verify(accounts, never()).saveLoginTokens(any(Long.class), any(), any());
        assertThat(grant.getValue().refreshToken()).isEqualTo("refresh-value");
        assertThat(grant.getValue().scopes()).contains(calendar);
        assertThat(response.getRedirectedUrl()).isEqualTo("/onboarding/cbi");
    }

    @Test
    void 토큰_저장에_실패하면_캘린더가_없는_것으로_보고_세션을_끊고_클라이언트는_지운다() throws Exception {
        when(clients.loadAuthorizedClient("google-calendar", "42"))
                .thenReturn(client("google-calendar", "r", "openid"));
        doThrow(new IllegalStateException("db down")).when(accounts)
                .saveCalendarTokens(any(Long.class), any(), any());
        when(calendar.isConnected(42L)).thenReturn(false);
        MockHttpServletRequest request = withSession();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication("google-calendar"));

        verify(clients).removeAuthorizedClient("google-calendar", "42");
        assertThat(((MockHttpSession) request.getSession(false))).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/?authError=true");
    }

    @Test
    void 일반_로그인인데_캘린더가_연결되지_않았으면_세션을_끊고_캘린더_권한_화면으로_보낸다() throws Exception {
        // 처음 가입하는 사람, 또는 연결 해제로 철회 대기 중인 사람
        when(clients.loadAuthorizedClient("google", "42")).thenReturn(client("google", null, "openid", "email"));
        when(calendar.isConnected(42L)).thenReturn(false);
        MockHttpServletRequest request = withSession();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication("google"));

        verify(accounts).saveLoginTokens(eq(42L), eq("google-sub"), any());
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/oauth2/authorization/google-calendar");
    }

    @Test
    void 캘린더_권한_화면에서_캘린더를_허용하지_않았으면_세션을_끊고_시작_화면으로_보낸다() throws Exception {
        // 구글 동의 화면에서 캘린더 항목 체크를 해제해 범위 없이 돌아왔다.
        when(clients.loadAuthorizedClient("google-calendar", "42"))
                .thenReturn(client("google-calendar", "r", "openid", "email"));
        when(calendar.isConnected(42L)).thenReturn(false);
        MockHttpServletRequest request = withSession();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication("google-calendar"));

        assertThat(request.getSession(false)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/?authError=true");
    }

    private static MockHttpServletRequest withSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        return request;
    }

    @Test
    void 인증_클라이언트가_없으면_저장하지_않고_리다이렉트만_한다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication("google"));

        verify(accounts, never()).saveLoginTokens(any(Long.class), any(), any());
        verify(accounts, never()).saveCalendarTokens(any(Long.class), any(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }
}
