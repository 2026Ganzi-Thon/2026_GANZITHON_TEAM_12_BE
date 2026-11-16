package com.comedown.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.comedown.account.application.GoogleAccountService.TokenGrant;
import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.account.contract.GoogleTokenRefresher.RefreshedToken;
import com.comedown.integration.google.GoogleOAuthClient;
import com.comedown.account.infrastructure.crypto.TokenCipher;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** 고정 시각 2026-11-11T22:30:00Z 기준으로 토큰 만료·갱신·오류 매핑을 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
@Transactional
class CalendarCredentialServiceIntegrationTest {

    private static final String CALENDAR = "https://www.googleapis.com/auth/calendar.events";
    private static final Instant NOW = Instant.parse("2026-11-11T22:30:00Z");

    @Autowired
    CalendarCredentialProvider provider;

    @Autowired
    GoogleAccountService accounts;

    @Autowired
    TokenCipher cipher;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    GoogleOAuthClient google;

    private long user(String sub) {
        return accounts.loginOrRegister(sub, null).userId();
    }

    private void tokens(long userId, String sub, String refresh, Instant expiresAt, String... scopes) {
        accounts.saveCalendarTokens(userId, sub, new TokenGrant("access-old", refresh, NOW.minusSeconds(3000), expiresAt,
                Set.of(scopes)));
    }

    private ErrorCode codeOf(long userId) {
        return ((ApiException) org.assertj.core.api.Assertions.catchThrowable(
                () -> provider.credentialFor(userId))).errorCode();
    }

    @Test
    void 토큰이_없으면_CALENDAR_NOT_CONNECTED() {
        assertThat(codeOf(user("s1"))).isEqualTo(ErrorCode.CALENDAR_NOT_CONNECTED);
    }

    @Test
    void 캘린더_범위가_없으면_CALENDAR_SCOPE_MISSING() {
        long id = user("s2");
        tokens(id, "s2", null, NOW.plusSeconds(3000), "openid", "email");

        assertThat(codeOf(id)).isEqualTo(ErrorCode.CALENDAR_SCOPE_MISSING);
    }

    @Test
    void 만료되지_않았으면_저장된_토큰을_복호화해_돌려주고_구글을_부르지_않는다() {
        long id = user("s3");
        tokens(id, "s3", "refresh-1", NOW.plusSeconds(3000), "openid", CALENDAR);

        assertThat(provider.credentialFor(id).accessToken()).isEqualTo("access-old");
        verify(google, never()).refresh(any(), any());
    }

    @Test
    void 만료됐으면_refresh_token으로_갱신해_저장하고_새_토큰을_돌려준다() {
        long id = user("s4");
        tokens(id, "s4", "refresh-1", NOW.minusSeconds(60), "openid", CALENDAR);
        when(google.refresh("refresh-1", NOW))
                .thenReturn(new RefreshedToken("access-new", NOW, NOW.plusSeconds(3599)));

        assertThat(provider.credentialFor(id).accessToken()).isEqualTo("access-new");

        String storedAccess = jdbc.queryForObject("SELECT access_token_enc FROM oauth_tokens WHERE user_id = ?",
                String.class, id);
        String storedRefresh = jdbc.queryForObject("SELECT refresh_token_enc FROM oauth_tokens WHERE user_id = ?",
                String.class, id);
        assertThat(cipher.decrypt(storedAccess)).isEqualTo("access-new");
        assertThat(cipher.decrypt(storedRefresh)).isEqualTo("refresh-1");
    }

    @Test
    void 곧_만료되면_미리_갱신한다() {
        long id = user("s5");
        tokens(id, "s5", "refresh-1", NOW.plusSeconds(30), "openid", CALENDAR);
        when(google.refresh("refresh-1", NOW)).thenReturn(new RefreshedToken("access-new", NOW, NOW.plusSeconds(3599)));

        assertThat(provider.credentialFor(id).accessToken()).isEqualTo("access-new");
    }

    @Test
    void 만료됐는데_refresh_token이_없으면_CALENDAR_REAUTH_REQUIRED() {
        long id = user("s6");
        tokens(id, "s6", null, NOW.minusSeconds(60), "openid", CALENDAR);

        assertThat(codeOf(id)).isEqualTo(ErrorCode.CALENDAR_REAUTH_REQUIRED);
        verify(google, never()).refresh(any(), any());
    }

    @Test
    void 갱신이_거부되면_CALENDAR_REAUTH_REQUIRED_구글_장애면_CALENDAR_API_ERROR() {
        long id = user("s7");
        tokens(id, "s7", "refresh-1", NOW.minusSeconds(60), "openid", CALENDAR);
        doThrow(new ExternalServiceException(Kind.UNAUTHORIZED, "revoked")).when(google).refresh("refresh-1", NOW);
        assertThat(codeOf(id)).isEqualTo(ErrorCode.CALENDAR_REAUTH_REQUIRED);

        doThrow(new ExternalServiceException(Kind.FAILURE, "down")).when(google).refresh("refresh-1", NOW);
        assertThat(codeOf(id)).isEqualTo(ErrorCode.CALENDAR_API_ERROR);
    }

    @Test
    void 자격_객체는_토큰을_문자열로_드러내지_않는다() {
        long id = user("s8");
        tokens(id, "s8", "r", NOW.plusSeconds(3000), "openid", CALENDAR);

        assertThat(provider.credentialFor(id).toString()).doesNotContain("access-old");
        assertThatThrownBy(() -> provider.credentialFor(user("s9"))).isInstanceOf(ApiException.class);
    }
}
