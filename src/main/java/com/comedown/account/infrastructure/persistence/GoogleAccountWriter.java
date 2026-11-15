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

    /** 구글 sub로 사용자를 찾아 로그인 시각·이메일을 갱신하고, 없으면 가입(PROFILE 단계)시킨다. */
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
            // 같은 계정의 동시 첫 로그인: 먼저 들어간 행을 쓴다.
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

    /**
     * 사용자의 구글 토큰을 저장한다(사용자당 1행). 새 refresh token이 없으면 저장된 값을 유지하고,
     * scopes는 구글이 돌려준 실제 허용 범위로 바꾼다.
     */
    public void saveTokens(long userId, String googleSub, String accessToken, String refreshToken,
            Instant issuedAt, Instant expiresAt, String scopes, Instant now) {
        jdbc.sql("""
                        INSERT INTO oauth_tokens (user_id, provider, principal_name, access_token_enc,
                            refresh_token_enc, access_token_issued_at, access_token_expires_at, scopes, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) AS v
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
                        """)
                .params(userId, PROVIDER, googleSub, cipher.encrypt(accessToken),
                        refreshToken == null ? null : cipher.encrypt(refreshToken),
                        issuedAt == null ? null : utc(issuedAt), expiresAt == null ? null : utc(expiresAt),
                        scopes, utc(now))
                .update();
    }

    /** 철회 요청 표시(연결이 숨겨진다). 토큰 행이 없으면 아무 일도 하지 않는다. */
    public void requestRevoke(long userId, Instant now) {
        jdbc.sql("UPDATE oauth_tokens SET revoke_requested_at = COALESCE(revoke_requested_at, ?) WHERE user_id = ?")
                .params(utc(now), userId).update();
    }

    /** 철회가 끝났으므로 토큰을 지운다. */
    public void deleteTokens(long userId) {
        jdbc.sql("DELETE FROM oauth_tokens WHERE user_id = ?").params(userId).update();
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
