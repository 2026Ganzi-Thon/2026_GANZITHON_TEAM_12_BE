package com.comedown.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.comedown.account.application.GoogleAccountService;
import com.comedown.account.application.GoogleAccountService.GoogleLogin;
import com.comedown.shared.security.ComeDownPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class AccountOidcUserServiceTest {

    @SuppressWarnings("unchecked")
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);
    private final GoogleAccountService accounts = mock(GoogleAccountService.class);
    private final AccountOidcUserService service = new AccountOidcUserService(delegate, accounts);

    private OidcUser googleUser(boolean emailVerified) {
        OidcIdToken token = new OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("sub", "google-sub", "email", "me@example.com", "email_verified", emailVerified));
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), token);
    }

    @Test
    void 구글_계정으로_Come_down_사용자를_만들고_users_id를_principal에_담는다() {
        when(delegate.loadUser(any())).thenReturn(googleUser(true));
        when(accounts.loginOrRegister("google-sub", "me@example.com")).thenReturn(new GoogleLogin(42, false));

        OidcUser user = service.loadUser(mock(OidcUserRequest.class));

        assertThat(((ComeDownPrincipal) user).userId()).isEqualTo(42);
        assertThat(user.getName()).isEqualTo("42"); // 세션 색인용 이름은 users.id
        assertThat(user.getSubject()).isEqualTo("google-sub");
    }

    @Test
    void 이메일이_확인되지_않았으면_저장하지_않는다() {
        when(delegate.loadUser(any())).thenReturn(googleUser(false));
        when(accounts.loginOrRegister(eq("google-sub"), eq(null))).thenReturn(new GoogleLogin(1, false));

        service.loadUser(mock(OidcUserRequest.class));

        verify(accounts).loginOrRegister("google-sub", null);
    }

    @Test
    void 탈퇴_진행_중인_계정은_로그인을_거부한다() {
        when(delegate.loadUser(any())).thenReturn(googleUser(true));
        when(accounts.loginOrRegister(any(), any())).thenReturn(new GoogleLogin(7, true));

        assertThatThrownBy(() -> service.loadUser(mock(OidcUserRequest.class)))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        e -> assertThat(e.getError().getErrorCode()).isEqualTo("account_deleting"));
    }
}
