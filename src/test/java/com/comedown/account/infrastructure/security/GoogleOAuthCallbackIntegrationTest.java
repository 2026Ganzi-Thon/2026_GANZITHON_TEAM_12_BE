package com.comedown.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.account.infrastructure.crypto.TokenCipher;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MySqlContainerTestConfig;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 구글에서 돌아오는 콜백({@code /login/oauth2/code/*})부터 세션·사용자·토큰 저장까지를 실제 필터 체인으로 확인한다.
 * 구글은 호출하지 않는다: 코드 교환과 ID 토큰 검증을 대체 응답으로 바꾸고, 사용자가 구글 계정 선택 화면에서 고른 계정은
 * 콜백의 code에 담아 흉내 낸다. 브라우저의 세션 쿠키는 {@link Browser}가 요청 사이에 이어 준다.
 */
@SpringBootTest(properties = {
        "comedown.google.client-id=test-client-id",
        "comedown.google.client-secret=test-client-secret"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class GoogleOAuthCallbackIntegrationTest {

    private static final String CALENDAR = GoogleOAuthConfig.CALENDAR_SCOPE;

    /** 구글 쪽에 캘린더 허용이 남아 있어 일반 로그인 응답에도 캘린더 범위가 함께 오는 상황(include_granted_scopes). */
    private static volatile boolean loginReturnsCalendarScope;

    /** 구글 동의 화면에서 캘린더 항목 체크를 해제한 상황(캘린더 권한 화면에서도 범위 없이 돌아옴). */
    private static volatile boolean calendarDeclined;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TokenCipher cipher;

    @Autowired
    OAuth2AuthorizedClientService authorizedClients;

    /** 운영 등록 정보에서 사용자 정보 주소만 뺀다(ID 토큰만으로 사용자를 만들고 구글 userinfo를 부르지 않게). */
    @TestBean
    ClientRegistrationRepository registrations;

    static ClientRegistrationRepository registrations() {
        return new InMemoryClientRegistrationRepository(
                withoutUserInfo(GoogleOAuthConfig.LOGIN_REGISTRATION, "openid", "email"),
                withoutUserInfo(GoogleOAuthConfig.CALENDAR_REGISTRATION, "openid", "email", CALENDAR));
    }

    private static ClientRegistration withoutUserInfo(String id, String... scopes) {
        return ClientRegistration
                .withClientRegistration(GoogleOAuthConfig.registration(id, "test-client-id", "test-client-secret",
                        scopes))
                .userInfoUri(null)
                .build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeGoogle {

        /** 코드 교환 대체 응답. code는 "{구글 sub}~{nonce}"이고 그대로 ID 토큰 값으로 돌려준다. */
        @Bean
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> fakeTokenEndpoint() {
            return request -> {
                String code = request.getAuthorizationExchange().getAuthorizationResponse().getCode();
                String sub = code.split("~")[0];
                boolean calendar = GoogleOAuthConfig.CALENDAR_REGISTRATION
                        .equals(request.getClientRegistration().getRegistrationId());
                Set<String> scopes = new LinkedHashSet<>(List.of("openid", "email"));
                if ((calendar && !calendarDeclined) || loginReturnsCalendarScope) {
                    scopes.add(CALENDAR);
                }
                OAuth2AccessTokenResponse.Builder response = OAuth2AccessTokenResponse
                        .withToken("access-" + (calendar ? "calendar-" : "login-") + sub)
                        .tokenType(OAuth2AccessToken.TokenType.BEARER)
                        .expiresIn(3600)
                        .scopes(scopes)
                        .additionalParameters(Map.of(OidcParameterNames.ID_TOKEN, code));
                if (calendar) {
                    response.refreshToken("refresh-calendar-" + sub); // offline + 동의 화면일 때만 온다
                }
                return response.build();
            };
        }

        /** ID 토큰 검증 대체: 위에서 돌려준 값("{sub}~{nonce}")을 구글이 서명한 ID 토큰으로 취급한다. */
        @Bean
        JwtDecoderFactory<ClientRegistration> fakeIdTokenDecoder() {
            return registration -> token -> {
                String[] parts = token.split("~");
                Instant now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .issuer("https://accounts.google.com")
                        .subject(parts[0])
                        .audience(List.of(registration.getClientId()))
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("nonce", parts[1])
                        .claim("email", parts[0] + "@example.com")
                        .claim("email_verified", true)
                        .build();
            };
        }
    }

    /** 요청 사이에 세션 쿠키를 이어 주는 브라우저 한 개. */
    private static final class Browser {
        private Cookie session;
    }

    @BeforeEach
    void setUp() {
        loginReturnsCalendarScope = false;
        calendarDeclined = false;
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'cb-%'");
    }

    @Test
    void 로그인하지_않은_방문자의_캘린더_연결은_가입_로그인하고_캘린더_토큰을_저장한다() throws Exception {
        Browser browser = new Browser();
        String sub = newSub();

        MvcResult callback = oauth(browser, "google-calendar", sub);

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/");
        long user = userId(sub);
        Map<String, Object> token = tokenRow(user);
        assertThat((String) token.get("scopes")).contains(CALENDAR);
        assertThat(cipher.decrypt((String) token.get("refresh_token_enc"))).isEqualTo("refresh-calendar-" + sub);
        send(browser, get("/api/me"), status().isOk(), jsonPath("$.id").value(user),
                jsonPath("$.calendar.scopeGranted").value(true));
    }

    @Test
    void 로그인한_사용자가_같은_구글_계정으로_캘린더를_연결하면_그_사용자에_연결된다() throws Exception {
        Browser browser = new Browser();
        String sub = newSub();
        oauth(new Browser(), "google-calendar", sub);
        // 캘린더가 연결된 사용자는 일반 로그인으로 들어온다(구글은 이전에 허용한 캘린더 범위를 로그인 토큰에도 준다).
        loginReturnsCalendarScope = true;
        assertThat(oauth(browser, "google", sub).getResponse().getRedirectedUrl()).isEqualTo("/");
        long user = userId(sub);

        MvcResult callback = oauth(browser, "google-calendar", sub);

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(userCount(sub)).isEqualTo(1);
        assertThat((String) tokenRow(user).get("scopes")).contains(CALENDAR);
        send(browser, get("/api/me"), status().isOk(), jsonPath("$.id").value(user),
                jsonPath("$.calendar.scopeGranted").value(true));
    }

    @Test
    void 로그인한_사용자가_캘린더_연결에서_다른_구글_계정을_고르면_거부하고_세션은_원래_사용자다() throws Exception {
        Browser browser = new Browser();
        String mine = newSub();
        String other = newSub();
        oauth(browser, "google-calendar", mine);
        long user = userId(mine);
        Map<String, Object> tokenBefore = tokenRow(user);
        int usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);

        // 구글 계정 선택 화면에서 다른 계정을 골랐다.
        MvcResult callback = oauth(browser, "google-calendar", other);

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/?authError=true");
        // 새 사용자도, 그 계정의 토큰도 생기지 않는다.
        assertThat(userCount(other)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_tokens WHERE principal_name = ?", Integer.class,
                other)).isZero();
        assertThat(tokenRow(user)).isEqualTo(tokenBefore);
        assertThat((Object) authorizedClients.loadAuthorizedClient("google-calendar", String.valueOf(user))).isNull();
        // 기존 세션은 원래 사용자로 로그인된 그대로이고 원래 캘린더 연결도 그대로다.
        send(browser, get("/api/me"), status().isOk(), jsonPath("$.id").value(user),
                jsonPath("$.email").value(mine + "@example.com"),
                jsonPath("$.calendar.scopeGranted").value(true));
    }

    @Test
    void 철회_대기_중에_일반_로그인하면_연결은_되살아나지_않고_세션_없이_캘린더_연결로_보낸다() throws Exception {
        String sub = newSub();
        oauth(new Browser(), "google-calendar", sub);
        long user = userId(sub);
        // 연결 해제의 구글 철회가 실패해 재시도를 기다리는 상태
        jdbc.update("UPDATE oauth_tokens SET revoke_requested_at = '2026-11-11 22:00:00' WHERE user_id = ?", user);
        Map<String, Object> pending = tokenRow(user);
        loginReturnsCalendarScope = true;

        Browser browser = new Browser();
        assertThat(oauth(browser, "google", sub).getResponse().getRedirectedUrl())
                .isEqualTo("/oauth2/authorization/google-calendar");

        // 보관한 토큰·철회 요청은 그대로이고, 캘린더가 끊긴 채로는 로그인 세션도 없다.
        assertThat(tokenRow(user)).isEqualTo(pending);
        send(browser, get("/api/me"), status().isUnauthorized());

        // 사용자가 캘린더 연결을 다시 허용해야 철회 요청이 풀리고 들어올 수 있다.
        assertThat(oauth(browser, "google-calendar", sub).getResponse().getRedirectedUrl())
                .isEqualTo("/");
        assertThat(tokenRow(user).get("revoke_requested_at")).isNull();
        send(browser, get("/api/me"), status().isOk(), jsonPath("$.calendar.connected").value(true),
                jsonPath("$.calendar.scopeGranted").value(true));
    }

    @Test
    void 캘린더_권한이_없는_첫_로그인은_세션을_만들지_않고_캘린더_권한_화면으로_보낸다() throws Exception {
        Browser browser = new Browser();
        String sub = newSub();

        MvcResult callback = oauth(browser, "google", sub);

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/oauth2/authorization/google-calendar");
        send(browser, get("/api/me"), status().isUnauthorized());

        // 이어서 캘린더 권한을 허용하면 그 사용자로 들어온다.
        assertThat(oauth(browser, "google-calendar", sub).getResponse().getRedirectedUrl())
                .isEqualTo("/");
        send(browser, get("/api/me"), status().isOk(), jsonPath("$.id").value(userId(sub)),
                jsonPath("$.calendar.scopeGranted").value(true));
    }

    @Test
    void 캘린더_권한_화면에서_캘린더를_허용하지_않으면_세션_없이_시작_화면으로_돌아간다() throws Exception {
        Browser browser = new Browser();
        String sub = newSub();
        calendarDeclined = true;

        MvcResult callback = oauth(browser, "google-calendar", sub);

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/?authError=true");
        send(browser, get("/api/me"), status().isUnauthorized());
        // 다시 일반 로그인해도 캘린더가 없으니 들어오지 못한다.
        assertThat(oauth(browser, "google", sub).getResponse().getRedirectedUrl())
                .isEqualTo("/oauth2/authorization/google-calendar");
        send(browser, get("/api/me"), status().isUnauthorized());
    }

    @Test
    void 로그인_뒤_캘린더가_끊긴_세션은_닉네임_저장을_포함한_API를_못_쓰고_세션도_끊긴다() throws Exception {
        Browser browser = new Browser();
        String sub = newSub();
        oauth(browser, "google-calendar", sub);
        long user = userId(sub);
        send(browser, get("/api/me"), status().isOk());
        // 결정 전에 만들어진 세션이거나 그 뒤 캘린더 연결을 해제한 상태
        jdbc.update("UPDATE oauth_tokens SET revoke_requested_at = '2026-11-11 22:00:00' WHERE user_id = ?", user);

        send(browser, get("/api/me"), status().isUnauthorized(), jsonPath("$.code").value("UNAUTHENTICATED"));
        // 세션이 끊겨 가입 정보(닉네임) 저장 단계에도 들어갈 수 없다.
        send(browser, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/onboarding/profile").contentType("application/json").content("{}")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf()),
                status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, user)).isNull();
    }

    // ---------- 도우미 ----------

    /**
     * OAuth 시작 주소로 이동한 뒤, 구글 계정 선택 화면에서 {@code googleSub} 계정을 고르고 콜백으로 돌아온 것처럼 요청한다.
     */
    private MvcResult oauth(Browser browser, String registrationId, String googleSub) throws Exception {
        MvcResult start = send(browser, get("/oauth2/authorization/" + registrationId), status().isFound());
        var params = UriComponentsBuilder.fromUriString(start.getResponse().getHeader("Location")).build()
                .getQueryParams();
        String state = URLDecoder.decode(params.getFirst("state"), StandardCharsets.UTF_8);
        String nonce = URLDecoder.decode(params.getFirst("nonce"), StandardCharsets.UTF_8);
        return send(browser, get("/login/oauth2/code/" + registrationId)
                .param("code", googleSub + "~" + nonce)
                .param("state", state), status().isFound());
    }

    private MvcResult send(Browser browser, MockHttpServletRequestBuilder request,
            org.springframework.test.web.servlet.ResultMatcher... expected) throws Exception {
        if (browser.session != null) {
            request.cookie(browser.session);
        }
        MvcResult result = mvc.perform(request).andExpectAll(expected).andReturn();
        Cookie issued = result.getResponse().getCookie("SESSION");
        if (issued != null) {
            browser.session = new Cookie("SESSION", issued.getValue());
        }
        return result;
    }

    private static String newSub() {
        return "cb-" + UUID.randomUUID();
    }

    private long userId(String sub) {
        return jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
    }

    private int userCount(String sub) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE google_sub = ?", Integer.class, sub);
    }

    private Map<String, Object> tokenRow(long user) {
        return jdbc.queryForMap("SELECT * FROM oauth_tokens WHERE user_id = ?", user);
    }
}
