package com.comedown.account.infrastructure.security;

import com.comedown.shared.security.ComeDownPrincipal;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * 로그인한 Come-down 사용자. 세션(JDBC)에 직렬화돼 저장된다.
 * 이름은 users.id 문자열이라 DevPrincipal처럼 사용자별 세션 색인(PRINCIPAL_NAME)으로 쓸 수 있다.
 */
public class ComeDownOidcUser extends DefaultOidcUser implements ComeDownPrincipal {

    private static final long serialVersionUID = 1L;

    private final long userId;

    public ComeDownOidcUser(long userId, Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken,
            OidcUserInfo userInfo) {
        super(authorities, idToken, userInfo);
        this.userId = userId;
    }

    @Override
    public long userId() {
        return userId;
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
