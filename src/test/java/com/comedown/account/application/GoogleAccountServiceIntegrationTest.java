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

        service.saveTokens(id, "sub-d", new TokenGrant("access-1", "refresh-1", issued, issued.plusSeconds(3600),
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
        service.saveTokens(id, "sub-e", new TokenGrant("access-1", "refresh-1", issued, issued.plusSeconds(3600),
                Set.of("openid", CALENDAR)));

        service.saveTokens(id, "sub-e", new TokenGrant("access-2", null, issued, issued.plusSeconds(3600),
                Set.of("openid")));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_tokens WHERE user_id = ?", Integer.class, id))
                .isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM oauth_tokens WHERE user_id = ?", id);
        assertThat(cipher.decrypt((String) row.get("access_token_enc"))).isEqualTo("access-2");
        assertThat(cipher.decrypt((String) row.get("refresh_token_enc"))).isEqualTo("refresh-1");
        assertThat(row.get("scopes")).isEqualTo("openid"); // 구글이 돌려준 실제 허용 범위를 따른다
    }

    @Test
    void 캘린더_권한을_저장하면_캘린더_연결로_판단된다() {
        // CalendarConnectionQuery는 schedule 쪽 PR과 별개로 main에 없을 수 있어 SQL 기준을 직접 확인한다.
        long id = service.loginOrRegister("sub-f", "f@example.com").userId();
        Instant issued = Instant.parse("2026-11-11T22:30:00Z");

        service.saveTokens(id, "sub-f", new TokenGrant("a", "r", issued, issued.plusSeconds(3600),
                Set.of("openid", "email", CALENDAR)));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM oauth_tokens WHERE user_id = ? AND LOCATE('auth/calendar', scopes) > 0",
                Integer.class, id)).isEqualTo(1);
    }
}
