package com.comedown.account.infrastructure.security;

import com.comedown.account.application.GoogleAccountService;
import com.comedown.account.application.GoogleAccountService.GoogleLogin;
import com.comedown.shared.security.ComeDownPrincipal;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * 구글이 확인해 준 계정(sub)으로 Come-down 사용자를 찾거나 가입시킨다. 탈퇴 진행 중인 계정은 거부한다.
 * <p>
 * 캘린더 연결({@code google-calendar})은 로그인한 사용자에게 권한을 붙이는 흐름이다. 이미 로그인한 세션에서 구글 계정
 * 선택 화면으로 다른 계정을 고르면 거부한다(새 사용자를 만들지 않고 토큰도 저장하지 않으며, 인증 실패로 끝나므로 기존
 * 세션은 원래 사용자로 남는다). 로그인하지 않은 방문자의 캘린더 연결은 로그인 + 연결로 그대로 동작한다.
 */
class AccountOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    static final String ACCOUNT_DELETING = "account_deleting";
    static final String ACCOUNT_MISMATCH = "account_mismatch";

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final GoogleAccountService accounts;

    AccountOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, GoogleAccountService accounts) {
        this.delegate = delegate;
        this.accounts = accounts;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        // 계정 확인은 검증이 끝난 ID 토큰의 sub로 하고, 다른 계정이면 사용자 정보 조회·가입 전에 끝낸다.
        Long sessionUserId = sessionUserId();
        if (sessionUserId != null && isCalendarConnection(userRequest)
                && !accounts.isGoogleAccountOf(sessionUserId, userRequest.getIdToken().getSubject())) {
            throw new OAuth2AuthenticationException(new OAuth2Error(ACCOUNT_MISMATCH));
        }
        OidcUser google = delegate.loadUser(userRequest);
        String email = Boolean.TRUE.equals(google.getEmailVerified()) ? google.getEmail() : null;
        GoogleLogin login = accounts.loginOrRegister(google.getSubject(), email);
        if (login.deleting()) {
            throw new OAuth2AuthenticationException(new OAuth2Error(ACCOUNT_DELETING));
        }
        return new ComeDownOidcUser(login.userId(), google.getAuthorities(), google.getIdToken(),
                google.getUserInfo());
    }

    private static boolean isCalendarConnection(OidcUserRequest userRequest) {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        return GoogleOAuthConfig.CALENDAR_REGISTRATION.equals(registrationId);
    }

    /** 이 요청(콜백)을 보낸 세션에 이미 로그인한 Come-down 사용자. 없으면 null. */
    private static Long sessionUserId() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null || current instanceof AnonymousAuthenticationToken || !current.isAuthenticated()
                || !(current.getPrincipal() instanceof ComeDownPrincipal principal)) {
            return null;
        }
        return principal.userId();
    }
}
