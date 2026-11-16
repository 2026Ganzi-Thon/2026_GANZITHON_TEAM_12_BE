package com.comedown.account.infrastructure.persistence;

import com.comedown.account.infrastructure.crypto.TokenCipher;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Google 로그인으로 users를, 권한 허용으로 oauth_tokens를 쓴다(account 소유 테이블).
 * DATETIME(6)에는 UTC 값을 쓰고, 토큰은 암호화한 값만 저장한다.
 */
@Repository
public class GoogleAccountWriter {

    static final String PROVIDER = "google";

    /** 로그인 결과. {@code deleting}이면 탈퇴 진행 중이라 로그인시키지 않는다. */
    public record LoginUser(long id, boolean deleting) {
    }

    private final JdbcClient jdbc;
    private final TokenCipher cipher;

    public GoogleAccountWriter(JdbcClient jdbc, TokenCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /**
     * 구글 sub로 사용자를 찾아 로그인 시각·이메일을 갱신하고, 없으면 가입(PROFILE 단계)시킨다.
     * <p>
     * 같은 계정의 첫 로그인이 겹치면 늦은 쪽 INSERT가 중복 키로 실패하고 먼저 들어간 행을 다시 읽는다. 이 다시 읽기가
     * 먼저 커밋된 행을 보려면 호출하는 트랜잭션이 READ COMMITTED여야 한다(REPEATABLE READ면 첫 조회 시점의 스냅샷만
     * 보여 행을 찾지 못한다).
     */
    public LoginUser loginOrRegister(String googleSub, String email, Instant now) {
        Optional<LoginUser> existing = update(googleSub, email, now);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            jdbc.sql("""
                            INSERT INTO users (google_sub, email, onboarding_step, created_at, last_login_at)
                            VALUES (?, ?, 'PROFILE', ?, ?)
                            """)
                    .params(googleSub, email, utc(now), utc(now))
                    .update();
        } catch (DuplicateKeyException e) {
            // 같은 계정의 동시 첫 로그인: 먼저 커밋된 행을 쓴다(아래 조회가 그 행을 본다).
        }
        return update(googleSub, email, now)
                .orElseThrow(() -> new IllegalStateException("가입 직후 사용자를 찾지 못했습니다."));
    }

    private Optional<LoginUser> update(String googleSub, String email, Instant now) {
        Optional<LoginUser> user = jdbc.sql("SELECT id, deletion_status FROM users WHERE google_sub = ?")
                .params(googleSub)
                .query((rs, n) -> new LoginUser(rs.getLong("id"), !"ACTIVE".equals(rs.getString("deletion_status"))))
                .optional();
        // 탈퇴 진행 중인 계정은 새 로그인으로 삭제를 되돌리지 않으므로 갱신하지 않는다.
        user.filter(u -> !u.deleting()).ifPresent(u ->
                jdbc.sql("UPDATE users SET last_login_at = ?, email = COALESCE(?, email) WHERE id = ?")
                        .params(utc(now), email, u.id())
                        .update());
        return user;
    }

    private static final String INSERT_TOKENS = """
            INSERT INTO oauth_tokens (user_id, provider, principal_name, access_token_enc,
                refresh_token_enc, access_token_issued_at, access_token_expires_at, scopes, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) AS v
            """;

    /** 캘린더 연결 허용: 새 토큰으로 바꾸고 남아 있던 철회 요청을 푼다(철회 대기 중이던 refresh token은 잇지 않는다). */
    private static final String ON_CALENDAR_CONNECTION = """
            ON DUPLICATE KEY UPDATE
                access_token_enc = v.access_token_enc,
                refresh_token_enc = IF(oauth_tokens.revoke_requested_at IS NOT NULL,
                    v.refresh_token_enc,
                    COALESCE(v.refresh_token_enc, oauth_tokens.refresh_token_enc)),
                revoke_requested_at = NULL,
                access_token_issued_at = v.access_token_issued_at,
                access_token_expires_at = v.access_token_expires_at,
                scopes = v.scopes,
                updated_at = v.updated_at
            """;

    /** 일반 로그인: 철회 요청이 남은 행은 어느 컬럼도 바꾸지 않는다(재시도가 보관한 토큰을 그대로 철회해야 한다). */
    private static final String ON_LOGIN = """
            ON DUPLICATE KEY UPDATE
                access_token_enc = IF(oauth_tokens.revoke_requested_at IS NULL,
                    v.access_token_enc, oauth_tokens.access_token_enc),
                refresh_token_enc = IF(oauth_tokens.revoke_requested_at IS NULL,
                    COALESCE(v.refresh_token_enc, oauth_tokens.refresh_token_enc), oauth_tokens.refresh_token_enc),
                access_token_issued_at = IF(oauth_tokens.revoke_requested_at IS NULL,
                    v.access_token_issued_at, oauth_tokens.access_token_issued_at),
                access_token_expires_at = IF(oauth_tokens.revoke_requested_at IS NULL,
                    v.access_token_expires_at, oauth_tokens.access_token_expires_at),
                scopes = IF(oauth_tokens.revoke_requested_at IS NULL, v.scopes, oauth_tokens.scopes),
                updated_at = IF(oauth_tokens.revoke_requested_at IS NULL, v.updated_at, oauth_tokens.updated_at)
            """;

    /**
     * 사용자의 구글 토큰을 저장한다(사용자당 1행). 새 refresh token이 없으면 저장된 값을 유지하고,
     * scopes는 구글이 돌려준 실제 허용 범위로 바꾼다.
     * <p>
     * 철회 요청이 남은 행은 {@code calendarConnection}(사용자가 캘린더 권한을 다시 허용한 경우)일 때만 새 토큰으로
     * 바꾸고 요청을 푼다. 일반 로그인은 그 행을 건드리지 않는다: 로그인 응답에는 이전에 허용한 캘린더 범위가 함께
     * 오므로(include_granted_scopes) 덮어쓰면 다시 동의하지 않았는데 연결된 것으로 보이고 철회도 잃는다.
     */
    public void saveTokens(long userId, String googleSub, String accessToken, String refreshToken,
            Instant issuedAt, Instant expiresAt, String scopes, boolean calendarConnection, Instant now) {
        jdbc.sql(INSERT_TOKENS + (calendarConnection ? ON_CALENDAR_CONNECTION : ON_LOGIN))
                .params(userId, PROVIDER, googleSub, cipher.encrypt(accessToken),
                        refreshToken == null ? null : cipher.encrypt(refreshToken),
                        issuedAt == null ? null : utc(issuedAt), expiresAt == null ? null : utc(expiresAt),
                        scopes, utc(now))
                .update();
    }

    /**
     * 철회 요청 표시(연결이 숨겨진다). 토큰 행이 없으면 아무 일도 하지 않는다. 동기화한 일정도 함께 지워지므로
     * 마지막 동기화 시각을 비운다(철회 대기 중에 다시 연결해도 이전 시각이 남지 않는다).
     */
    public void requestRevoke(long userId, Instant now) {
        jdbc.sql("UPDATE oauth_tokens SET revoke_requested_at = COALESCE(revoke_requested_at, ?), "
                        + "calendar_synced_at = NULL WHERE user_id = ?")
                .params(utc(now), userId).update();
    }

    /**
     * 철회가 끝났으므로(또는 더는 철회할 수 없으므로) 철회 요청이 남은 토큰을 지운다. 구글 호출은 트랜잭션 밖이라
     * 그 사이 다시 연결해 철회 요청이 풀린 새 토큰 행은 지우지 않는다.
     */
    public void deletePendingRevoke(long userId) {
        jdbc.sql("DELETE FROM oauth_tokens WHERE user_id = ? AND revoke_requested_at IS NOT NULL")
                .params(userId).update();
    }

    /** 철회 시도가 실패한 행을 재시도 순서의 맨 뒤로 보낸다(순서는 updated_at 기준). */
    public void markRevokeAttempted(long userId, Instant now) {
        jdbc.sql("UPDATE oauth_tokens SET updated_at = ? WHERE user_id = ? AND revoke_requested_at IS NOT NULL")
                .params(utc(now), userId).update();
    }

    /** 갱신한 access token만 바꾼다(refresh token·scopes는 그대로). */
    public void updateAccessToken(long userId, String accessToken, Instant issuedAt, Instant expiresAt, Instant now) {
        jdbc.sql("""
                        UPDATE oauth_tokens
                           SET access_token_enc = ?, access_token_issued_at = ?, access_token_expires_at = ?,
                               updated_at = ?
                         WHERE user_id = ?
                        """)
                .params(cipher.encrypt(accessToken), utc(issuedAt), utc(expiresAt), utc(now), userId)
                .update();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
