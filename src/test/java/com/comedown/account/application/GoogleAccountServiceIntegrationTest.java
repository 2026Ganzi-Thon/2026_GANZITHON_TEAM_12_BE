package com.comedown.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.comedown.account.application.GoogleAccountService.GoogleLogin;
import com.comedown.account.application.GoogleAccountService.TokenGrant;
import com.comedown.account.infrastructure.crypto.TokenCipher;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** MySQL 위에서 가입·로그인·토큰 암호화 저장을 확인한다. 고정 시각은 2026-11-12 07:30 KST(= 22:30Z). */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
@Transactional
class GoogleAccountServiceIntegrationTest {

    private static final String CALENDAR = "https://www.googleapis.com/auth/calendar.events";

    @Autowired
    GoogleAccountService service;

    @Autowired
    TokenCipher cipher;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 처음_로그인하면_PROFILE_단계로_가입한다() {
        GoogleLogin login = service.loginOrRegister("sub-a", "a@example.com");

        Map<String, Object> user = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", login.userId());
        assertThat(login.deleting()).isFalse();
        assertThat(user.get("google_sub")).isEqualTo("sub-a");
        assertThat(user.get("email")).isEqualTo("a@example.com");
        assertThat(user.get("onboarding_step")).isEqualTo("PROFILE");
        assertThat(user.get("deletion_status")).isEqualTo("ACTIVE");
        assertThat(user.get("nickname")).isNull();
    }

    @Test
    void 다시_로그인하면_같은_사용자이고_로그인_시각만_갱신된다() {
        long first = service.loginOrRegister("sub-b", "b@example.com").userId();
        jdbc.update("UPDATE users SET last_login_at = '2026-01-01 00:00:00' WHERE id = ?", first);

        long second = service.loginOrRegister("sub-b", null).userId();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE google_sub = 'sub-b'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM users WHERE id = ?",
                java.sql.Timestamp.class, first).toLocalDateTime().toString()).startsWith("2026-11-11T22:30");
        // 이메일을 못 받은 로그인이 기존 이메일을 지우지 않는다.
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, first))
                .isEqualTo("b@example.com");
    }

    @Test
    void 탈퇴_진행_중인_계정은_로그인으로_갱신하지_않고_deleting으로_알린다() {
        long id = service.loginOrRegister("sub-c", "c@example.com").userId();
        jdbc.update("UPDATE users SET deletion_status = 'DELETING', last_login_at = '2026-01-01 00:00:00' "
                + "WHERE id = ?", id);

        GoogleLogin login = service.loginOrRegister("sub-c", "c@example.com");

        assertThat(login.userId()).isEqualTo(id);
        assertThat(login.deleting()).isTrue();
        assertThat(jdbc.queryForObject("SELECT deletion_status FROM users WHERE id = ?", String.class, id))
                .isEqualTo("DELETING");
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM users WHERE id = ?",
                java.sql.Timestamp.class, id).toLocalDateTime().toString()).startsWith("2026-01-01");
    }

    @Test
    void 토큰은_암호화해_저장하고_복호화하면_원문이다() {
        long id = service.loginOrRegister("sub-d", "d@example.com").userId();
        Instant issued = Instant.parse("2026-11-11T22:30:00Z");

        service.saveCalendarTokens(id, "sub-d", new TokenGrant("access-1", "refresh-1", issued, issued.plusSeconds(3600),
                Set.of("openid", "email", CALENDAR)));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM oauth_tokens WHERE user_id = ?", id);
        assertThat(row.get("provider")).isEqualTo("google");
        assertThat(row.get("principal_name")).isEqualTo("sub-d");
        assertThat((String) row.get("access_token_enc")).doesNotContain("access-1");
        assertThat((String) row.get("refresh_token_enc")).doesNotContain("refresh-1");
        assertThat(cipher.decrypt((String) row.get("access_token_enc"))).isEqualTo("access-1");
        assertThat(cipher.decrypt((String) row.get("refresh_token_enc"))).isEqualTo("refresh-1");
        assertThat((String) row.get("scopes")).contains(CALENDAR);
    }

    @Test
    void 다시_저장해도_행은_하나이고_새_refresh_token이_없으면_기존_값을_유지한다() {
        long id = service.loginOrRegister("sub-e", "e@example.com").userId();
        Instant issued = Instant.parse("2026-11-11T22:30:00Z");
        service.saveCalendarTokens(id, "sub-e", new TokenGrant("access-1", "refresh-1", issued, issued.plusSeconds(3600),
                Set.of("openid", CALENDAR)));

        service.saveCalendarTokens(id, "sub-e", new TokenGrant("access-2", null, issued, issued.plusSeconds(3600),
                Set.of("openid")));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_tokens WHERE user_id = ?", Integer.class, id))
                .isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM oauth_tokens WHERE user_id = ?", id);
        assertThat(cipher.decrypt((String) row.get("access_token_enc"))).isEqualTo("access-2");
        assertThat(cipher.decrypt((String) row.get("refresh_token_enc"))).isEqualTo("refresh-1");
        assertThat(row.get("scopes")).isEqualTo("openid"); // 구글이 돌려준 실제 허용 범위를 따른다
    }

    @Test
    void 일반_로그인_토큰도_저장되고_다시_로그인하면_갱신된다() {
        long id = service.loginOrRegister("sub-g", "g@example.com").userId();
        Instant issued = Instant.parse("2026-11-11T22:30:00Z");

        service.saveLoginTokens(id, "sub-g", new TokenGrant("access-1", null, issued, issued.plusSeconds(3600),
                Set.of("openid", "email")));
        service.saveLoginTokens(id, "sub-g", new TokenGrant("access-2", null, issued, issued.plusSeconds(3600),
                Set.of("openid", "email")));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM oauth_tokens WHERE user_id = ?", id);
        assertThat(cipher.decrypt((String) row.get("access_token_enc"))).isEqualTo("access-2");
        assertThat(row.get("revoke_requested_at")).isNull();
    }

    @Test
    void 철회_대기_중인_토큰은_일반_로그인이_건드리지_않는다() {
        long id = service.loginOrRegister("sub-h", "h@example.com").userId();
        Instant issued = Instant.parse("2026-11-11T22:30:00Z");
        service.saveCalendarTokens(id, "sub-h", new TokenGrant("access-1", "refresh-1", issued,
                issued.plusSeconds(3600), Set.of("openid", CALENDAR)));
        // 연결 해제·탈퇴의 구글 철회가 실패해 재시도를 기다리는 상태
        jdbc.update("UPDATE oauth_tokens SET revoke_requested_at = '2026-11-11 22:00:00', "
                + "updated_at = '2026-11-11 21:00:00' WHERE user_id = ?", id);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM oauth_tokens WHERE user_id = ?", id);

        // 로그인 응답에는 이전에 허용한 캘린더 범위가 함께 온다(include_granted_scopes).
        service.saveLoginTokens(id, "sub-h", new TokenGrant("access-2", "refresh-2", issued.plusSeconds(60),
                issued.plusSeconds(3660), Set.of("openid", "email", CALENDAR)));

        Map<String, Object> after = jdbc.queryForMap("SELECT * FROM oauth_tokens WHERE user_id = ?", id);
        assertThat(after).isEqualTo(before); // 철회 요청·암호화 토큰·scopes·시각 모두 그대로
        assertThat(after.get("revoke_requested_at")).isNotNull();
        assertThat(cipher.decrypt((String) after.get("refresh_token_enc"))).isEqualTo("refresh-1");
    }

    @Test
    void 캘린더_연결은_철회_요청을_풀고_새_토큰으로_바꾼다() {
        long id = service.loginOrRegister("sub-i", "i@example.com").userId();
        Instant issued = Instant.parse("2026-11-11T22:30:00Z");
        service.saveCalendarTokens(id, "sub-i", new TokenGrant("access-1", "refresh-1", issued,
                issued.plusSeconds(3600), Set.of("openid", CALENDAR)));
        jdbc.update("UPDATE oauth_tokens SET revoke_requested_at = '2026-11-11 22:00:00' WHERE user_id = ?", id);

        service.saveCalendarTokens(id, "sub-i", new TokenGrant("access-2", "refresh-2", issued,
                issued.plusSeconds(3600), Set.of("openid", "email", CALENDAR)));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM oauth_tokens WHERE user_id = ?", id);
        assertThat(row.get("revoke_requested_at")).isNull();
        assertThat(cipher.decrypt((String) row.get("access_token_enc"))).isEqualTo("access-2");
        assertThat(cipher.decrypt((String) row.get("refresh_token_enc"))).isEqualTo("refresh-2");
    }

    @Test
    void 구글_계정이_그_사용자의_계정인지_확인한다() {
        long mine = service.loginOrRegister("sub-j", "j@example.com").userId();
        long other = service.loginOrRegister("sub-k", "k@example.com").userId();

        assertThat(service.isGoogleAccountOf(mine, "sub-j")).isTrue();
        assertThat(service.isGoogleAccountOf(mine, "sub-k")).isFalse();
        assertThat(service.isGoogleAccountOf(other, "sub-j")).isFalse();
        assertThat(service.isGoogleAccountOf(mine, "sub-unknown")).isFalse();
        assertThat(service.isGoogleAccountOf(-1, "sub-j")).isFalse(); // 없는 사용자
    }

    @Test
    void 캘린더_권한을_저장하면_캘린더_연결로_판단된다() {
        // CalendarConnectionQuery는 schedule 쪽 PR과 별개로 main에 없을 수 있어 SQL 기준을 직접 확인한다.
        long id = service.loginOrRegister("sub-f", "f@example.com").userId();
        Instant issued = Instant.parse("2026-11-11T22:30:00Z");

        service.saveCalendarTokens(id, "sub-f", new TokenGrant("a", "r", issued, issued.plusSeconds(3600),
                Set.of("openid", "email", CALENDAR)));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM oauth_tokens WHERE user_id = ? AND LOCATE('auth/calendar', scopes) > 0",
                Integer.class, id)).isEqualTo(1);
    }
}
