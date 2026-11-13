package com.comedown.shared.security.dev;

import com.comedown.shared.security.ComeDownPrincipal;
import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * local 전용 테스트 로그인 principal. Spring Session(JDBC)에 직렬화돼 저장된다.
 * 이름은 users.id 문자열이라 세션 principal 색인(PRINCIPAL_NAME)으로 사용자별 세션을 찾을 수 있다.
 */
public record DevPrincipal(long userId) implements ComeDownPrincipal, AuthenticatedPrincipal, Serializable {

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
