package com.comedown.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.comedown.shared.security.ComeDownPrincipal;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** MockMvc 요청을 특정 users.id로 로그인한 상태로 만든다. */
public final class TestLogin {

    private TestLogin() {
    }

    public record TestPrincipal(long userId) implements ComeDownPrincipal {
    }

    public static RequestPostProcessor loginAs(long userId) {
        return authentication(new TestingAuthenticationToken(new TestPrincipal(userId), null, "ROLE_USER"));
    }
}
