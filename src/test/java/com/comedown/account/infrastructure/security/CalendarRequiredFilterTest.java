package com.comedown.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.shared.error.ErrorResponseWriter;
import com.comedown.shared.security.ComeDownPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import tools.jackson.databind.json.JsonMapper;

class CalendarRequiredFilterTest {

    private final CalendarConnectionQuery calendar = mock(CalendarConnectionQuery.class);
    private final CalendarRequiredFilter filter =
            new CalendarRequiredFilter(calendar, new ErrorResponseWriter(JsonMapper.builder().build()));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loggedIn(long userId) {
        Instant now = Instant.parse("2026-11-19T00:00:00Z");
        OidcIdToken idToken = new OidcIdToken("t", now, now.plusSeconds(60), Map.of("sub", "s"));
        ComeDownOidcUser user = new ComeDownOidcUser(userId, List.of(new SimpleGrantedAuthority("OIDC_USER")),
                idToken, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        assertThat(user).isInstanceOf(ComeDownPrincipal.class);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setSession(new MockHttpSession());
        return request;
    }

    @Test
    void 캘린더가_연결되지_않은_세션의_API_요청은_세션을_끊고_401() throws Exception {
        loggedIn(7);
        when(calendar.isConnected(7)).thenReturn(false);
        MockHttpServletRequest request = request("PUT", "/api/onboarding/profile");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHENTICATED").contains("캘린더 권한");
        assertThat(chain.getRequest()).isNull(); // 컨트롤러까지 가지 않는다
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void 캘린더가_연결된_세션은_그대로_통과한다() throws Exception {
        loggedIn(7);
        when(calendar.isConnected(7)).thenReturn(true);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("GET", "/api/me"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 로그인하지_않은_요청은_확인하지_않고_통과한다() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("GET", "/api/me"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(calendar);
    }

    @Test
    void 로그아웃_CSRF_개발용_API가_아닌_경로는_확인하지_않는다() throws Exception {
        loggedIn(7);
        for (String path : List.of("/api/auth/logout", "/api/auth/csrf", "/api/dev/login", "/oauth2/authorization/google",
                "/onboarding/cbi")) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request("POST", path), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(path).isNotNull();
        }
        verifyNoInteractions(calendar);
    }
}
