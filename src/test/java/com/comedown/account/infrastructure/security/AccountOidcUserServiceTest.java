package com.comedown.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.comedown.account.application.GoogleAccountService;
import com.comedown.account.application.GoogleAccountService.GoogleLogin;
import com.comedown.shared.security.ComeDownPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class AccountOidcUserServiceTest {

    private static final Instant ISSUED = Instant.parse("2026-11-11T22:30:00Z");

    @SuppressWarnings("unchecked")
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);
    private final GoogleAccountService accounts = mock(GoogleAccountService.class);
    private final AccountOidcUserService service = new AccountOidcUserService(delegate, accounts);

    @AfterEach
    void clearSession() {
        SecurityContextHolder.clearContext();
    }

    private OidcIdToken idToken(boolean emailVerified) {
        return new OidcIdToken("t", ISSUED, ISSUED.plusSeconds(60),
                Map.of("sub", "google-sub", "email", "me@example.com", "email_verified", emailVerified));
    }

    private OidcUser googleUser(boolean emailVerified) {
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken(emailVerified));
    }

    /** 구글이 돌려준 계정은 항상 "google-sub"이다. */
    private OidcUserRequest request(String registrationId) {
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-value",
                ISSUED, ISSUED.plusSeconds(3600), Set.of("openid", "email"));
        return new OidcUserRequest(GoogleOAuthConfig.registration(registrationId, "id", "secret", "openid", "email"),
                access, idToken(true));
    }

    /** 이미 로그인한 세션(users.id = userId). */
    private void loggedInAs(long userId) {
        ComeDownOidcUser user = new ComeDownOidcUser(userId, List.of(new SimpleGrantedAuthority("OIDC_USER")),
                new OidcIdToken("old", ISSUED, ISSUED.plusSeconds(60), Map.of("sub", "session-sub")), null);
        session(new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));
    }

    private void session(Authentication authentication) {
        SecurityContextHolder.setContext(new SecurityContextImpl(authentication));
    }

    @Test
    void 구글_계정으로_Come_down_사용자를_만들고_users_id를_principal에_담는다() {
        when(delegate.loadUser(any())).thenReturn(googleUser(true));
        when(accounts.loginOrRegister("google-sub", "me@example.com")).thenReturn(new GoogleLogin(42, false));

        OidcUser user = service.loadUser(request("google"));

        assertThat(((ComeDownPrincipal) user).userId()).isEqualTo(42);
        assertThat(user.getName()).isEqualTo("42"); // 세션 색인용 이름은 users.id
        assertThat(user.getSubject()).isEqualTo("google-sub");
    }

    @Test
    void 이메일이_확인되지_않았으면_저장하지_않는다() {
        when(delegate.loadUser(any())).thenReturn(googleUser(false));
        when(accounts.loginOrRegister(eq("google-sub"), eq(null))).thenReturn(new GoogleLogin(1, false));

        service.loadUser(request("google"));

        verify(accounts).loginOrRegister("google-sub", null);
    }

    @Test
    void 탈퇴_진행_중인_계정은_로그인을_거부한다() {
        when(delegate.loadUser(any())).thenReturn(googleUser(true));
        when(accounts.loginOrRegister(any(), any())).thenReturn(new GoogleLogin(7, true));

        assertThatThrownBy(() -> service.loadUser(request("google")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        e -> assertThat(e.getError().getErrorCode()).isEqualTo("account_deleting"));
    }

    @Test
    void 로그인한_채_캘린더_연결에서_다른_구글_계정을_고르면_거부하고_가입시키지_않는다() {
        loggedInAs(42);
        when(accounts.isGoogleAccountOf(42, "google-sub")).thenReturn(false);

        assertThatThrownBy(() -> service.loadUser(request("google-calendar")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        e -> assertThat(e.getError().getErrorCode()).isEqualTo("account_mismatch"));

        verify(accounts, never()).loginOrRegister(any(), any());
        verify(delegate, never()).loadUser(any()); // 다른 계정의 토큰으로 사용자 정보를 조회하지 않는다
    }

    @Test
    void 로그인한_채_캘린더_연결에서_같은_구글_계정이면_그_사용자로_이어진다() {
        loggedInAs(42);
        when(accounts.isGoogleAccountOf(42, "google-sub")).thenReturn(true);
        when(delegate.loadUser(any())).thenReturn(googleUser(true));
        when(accounts.loginOrRegister("google-sub", "me@example.com")).thenReturn(new GoogleLogin(42, false));

        OidcUser user = service.loadUser(request("google-calendar"));

        assertThat(((ComeDownPrincipal) user).userId()).isEqualTo(42);
    }

    @Test
    void 로그인하지_않은_방문자의_캘린더_연결은_로그인과_연결로_동작한다() {
        session(new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        when(delegate.loadUser(any())).thenReturn(googleUser(true));
        when(accounts.loginOrRegister("google-sub", "me@example.com")).thenReturn(new GoogleLogin(9, false));

        OidcUser user = service.loadUser(request("google-calendar"));

        assertThat(((ComeDownPrincipal) user).userId()).isEqualTo(9);
        verify(accounts, never()).isGoogleAccountOf(anyLong(), any());
    }

    @Test
    void 세션의_principal이_서비스_사용자가_아니면_로그인하지_않은_것으로_본다() {
        session(new TestingAuthenticationToken("someone", null, "ROLE_USER"));
        when(delegate.loadUser(any())).thenReturn(googleUser(true));
        when(accounts.loginOrRegister("google-sub", "me@example.com")).thenReturn(new GoogleLogin(9, false));

        service.loadUser(request("google-calendar"));

        verify(accounts, never()).isGoogleAccountOf(anyLong(), any());
    }

    @Test
    void 일반_로그인은_로그인한_세션이_있어도_계정을_묶어_확인하지_않는다() {
        loggedInAs(42);
        when(delegate.loadUser(any())).thenReturn(googleUser(true));
        when(accounts.loginOrRegister("google-sub", "me@example.com")).thenReturn(new GoogleLogin(77, false));

        OidcUser user = service.loadUser(request("google"));

        assertThat(((ComeDownPrincipal) user).userId()).isEqualTo(77);
        verify(accounts, never()).isGoogleAccountOf(anyLong(), any());
    }
}
