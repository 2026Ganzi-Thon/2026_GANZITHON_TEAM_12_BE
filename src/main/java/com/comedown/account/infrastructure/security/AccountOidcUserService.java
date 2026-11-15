package com.comedown.account.infrastructure.security;

import com.comedown.account.application.GoogleAccountService;
import com.comedown.account.application.GoogleAccountService.GoogleLogin;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** 구글이 확인해 준 계정(sub)으로 Come-down 사용자를 찾거나 가입시킨다. 탈퇴 진행 중인 계정은 거부한다. */
class AccountOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    static final String ACCOUNT_DELETING = "account_deleting";

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final GoogleAccountService accounts;

    AccountOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, GoogleAccountService accounts) {
        this.delegate = delegate;
        this.accounts = accounts;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser google = delegate.loadUser(userRequest);
        String email = Boolean.TRUE.equals(google.getEmailVerified()) ? google.getEmail() : null;
        GoogleLogin login = accounts.loginOrRegister(google.getSubject(), email);
        if (login.deleting()) {
            throw new OAuth2AuthenticationException(new OAuth2Error(ACCOUNT_DELETING));
        }
        return new ComeDownOidcUser(login.userId(), google.getAuthorities(), google.getIdToken(),
                google.getUserInfo());
    }
}
