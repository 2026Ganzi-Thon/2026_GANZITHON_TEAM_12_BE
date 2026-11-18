package com.comedown.account.infrastructure.security;

import com.comedown.account.application.GoogleAccountService;
import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.account.infrastructure.crypto.TokenCipher;
import com.comedown.shared.error.ErrorResponseWriter;
import com.comedown.shared.security.SecurityFilterChainContributor;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;

/**
 * Google OAuth2 설정. {@code GOOGLE_CLIENT_ID}가 있을 때만 켜진다(없으면 로그인 경로 자체가 없다).
 * 켜질 때 {@code COMEDOWN_TOKEN_KEY}나 {@code GOOGLE_CLIENT_SECRET}이 비어 있으면 시작하지 않는다.
 * <ul>
 *   <li>{@code google}: 로그인. 기본 정보(openid, email)만 받는다(이름·프로필은 받지 않는다).</li>
 *   <li>{@code google-calendar}: 캘린더 권한 추가. 일정 읽기·쓰기 범위를 받고, refresh token을 받으려고
 *       offline + 동의 화면을 요청한다.</li>
 * </ul>
 * 로그인과 캘린더 허용은 같은 구글 계정이라 oauth_tokens 한 행에 합쳐 저장한다(include_granted_scopes).
 */
@Configuration(proxyBeanMethods = false)
@Conditional(GoogleOAuthConfig.Enabled.class)
class GoogleOAuthConfig {

    static final String LOGIN_REGISTRATION = "google";
    static final String CALENDAR_REGISTRATION = "google-calendar";
    static final String CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.events";

    static class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String clientId = context.getEnvironment().getProperty("comedown.google.client-id");
            return clientId != null && !clientId.isBlank();
        }
    }

    GoogleOAuthConfig(TokenCipher cipher, @Value("${comedown.google.client-secret:}") String clientSecret) {
        // 토큰을 저장할 수 없는 상태로 로그인이 열리지 않게 시작할 때 막는다.
        if (!cipher.isConfigured()) {
            throw new IllegalStateException(
                    "GOOGLE_CLIENT_ID를 설정하면 COMEDOWN_TOKEN_KEY(Base64 32바이트)도 설정해야 합니다.");
        }
        // 비밀 값 없이 열리면 모든 로그인이 구글의 코드 교환 단계에서 실패하므로 시작할 때 막는다.
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("GOOGLE_CLIENT_ID를 설정하면 GOOGLE_CLIENT_SECRET도 설정해야 합니다.");
        }
    }

    @Bean
    ClientRegistrationRepository googleClientRegistrations(
            @Value("${comedown.google.client-id}") String clientId,
            @Value("${comedown.google.client-secret:}") String clientSecret) {
        return new InMemoryClientRegistrationRepository(
                registration(LOGIN_REGISTRATION, clientId, clientSecret, "openid", "email"),
                registration(CALENDAR_REGISTRATION, clientId, clientSecret, "openid", "email", CALENDAR_SCOPE));
    }

    static ClientRegistration registration(String id, String clientId, String clientSecret,
            String... scopes) {
        return CommonOAuth2Provider.GOOGLE.getBuilder(id)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .scope(scopes)
                .build();
    }

    /** 구글 토큰은 성공 처리에서 암호화 저장 후 지우므로 메모리 저장소로 충분하다. */
    @Bean
    OAuth2AuthorizedClientService googleAuthorizedClientService(ClientRegistrationRepository registrations) {
        return new InMemoryOAuth2AuthorizedClientService(registrations);
    }

    @Bean
    SecurityFilterChainContributor googleOAuthLogin(ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService clients, GoogleAccountService accounts,
            CalendarConnectionQuery calendarConnection) {
        OAuth2AuthorizationRequestResolver resolver = authorizationRequestResolver(registrations);
        return http -> http.oauth2Login(login -> login
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                .userInfoEndpoint(info -> info.oidcUserService(new AccountOidcUserService(new OidcUserService(), accounts)))
                .successHandler(new GoogleOAuthSuccessHandler(clients, accounts, calendarConnection))
                .failureHandler((request, response, exception) -> response.sendRedirect("/?authError=true")));
    }

    /**
     * 캘린더 권한 없는 세션의 API 요청을 막는다({@link CalendarRequiredFilter}). 구글 로그인이 켜진 환경에서만 쓰며
     * {@code comedown.security.require-calendar=false}면 끈다(local의 테스트 로그인·시연 데이터용).
     */
    @Bean
    SecurityFilterChainContributor calendarRequired(CalendarConnectionQuery calendarConnection,
            ErrorResponseWriter errors,
            @Value("${comedown.security.require-calendar:true}") boolean requireCalendar) {
        if (!requireCalendar) {
            return http -> { };
        }
        CalendarRequiredFilter filter = new CalendarRequiredFilter(calendarConnection, errors);
        return http -> http.addFilterAfter(filter, AuthorizationFilter.class);
    }

    static OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository registrations) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(builder -> {
            AtomicReference<String> registrationId = new AtomicReference<>();
            builder.attributes(attrs -> registrationId.set((String) attrs.get(OAuth2ParameterNames.REGISTRATION_ID)));
            boolean calendar = CALENDAR_REGISTRATION.equals(registrationId.get());
            builder.additionalParameters(params -> {
                // 이전에 허용한 범위(캘린더)를 로그인 토큰에서도 유지한다.
                params.put("include_granted_scopes", "true");
                if (calendar) {
                    params.put("access_type", "offline");
                    params.put("prompt", "consent");
                }
            });
        });
        return resolver;
    }
}
