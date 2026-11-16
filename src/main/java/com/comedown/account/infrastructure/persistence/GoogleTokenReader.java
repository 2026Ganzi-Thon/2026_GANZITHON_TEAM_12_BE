package com.comedown.account.infrastructure.persistence;

import com.comedown.account.infrastructure.crypto.TokenCipher;
import com.comedown.account.infrastructure.crypto.TokenDecryptionException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** oauth_tokens에서 구글 토큰을 읽어 복호화한다(account 안에서만 쓴다). */
@Repository
public class GoogleTokenReader {

    /** 복호화된 토큰. {@link #toString()}에는 값이 드러나지 않는다. */
    public record StoredTokens(String accessToken, String refreshToken, Instant accessExpiresAt, String scopes) {

        @Override
        public String toString() {
            return "StoredTokens[scopes=" + scopes + "]";
        }

        public boolean hasCalendarScope() {
            return scopes != null && scopes.contains("auth/calendar");
        }
    }

    /**
     * 철회를 기다리는 토큰 행. 저장된 값을 지금 키로 복호화할 수 없으면(키 변경·암호문 손상) {@code tokens}가 null이고,
     * 그 토큰은 구글에 보낼 수 없다.
     */
    public record PendingRevoke(StoredTokens tokens) {

        public boolean decryptable() {
            return tokens != null;
        }
    }

    private final JdbcClient jdbc;
    private final TokenCipher cipher;

    public GoogleTokenReader(JdbcClient jdbc, TokenCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public Optional<StoredTokens> find(long userId) {
        return jdbc.sql("""
                        SELECT access_token_enc, refresh_token_enc, access_token_expires_at, scopes
                          FROM oauth_tokens WHERE user_id = ? AND revoke_requested_at IS NULL
                        """)
                .params(userId)
                .query(this::map)
                .optional();
    }

    /**
     * 철회를 요청했지만 아직 구글에서 철회하지 못한 토큰. 복호화 실패는 예외로 내보내지 않고
     * {@link PendingRevoke#decryptable()}로 알린다(철회 흐름이 그 행을 정리하고 끝낼 수 있게).
     */
    public Optional<PendingRevoke> findPendingRevoke(long userId) {
        return jdbc.sql("""
                        SELECT access_token_enc, refresh_token_enc, access_token_expires_at, scopes
                          FROM oauth_tokens WHERE user_id = ? AND revoke_requested_at IS NOT NULL
                        """)
                .params(userId)
                .query((rs, n) -> {
                    try {
                        return new PendingRevoke(map(rs, n));
                    } catch (TokenDecryptionException e) {
                        return new PendingRevoke(null);
                    }
                })
                .optional();
    }

    /**
     * 철회를 기다리는 사용자 id. 마지막으로 손댄 시각(updated_at)이 오래된 순서다: 철회 시도가 실패하면 그 시각을
     * 갱신하므로 계속 실패하는 행이 뒤로 밀리고 아직 시도하지 않은 행이 먼저 나온다.
     */
    public java.util.List<Long> usersWithPendingRevoke(int limit) {
        return jdbc.sql("SELECT user_id FROM oauth_tokens WHERE revoke_requested_at IS NOT NULL "
                        + "ORDER BY updated_at, id LIMIT ?")
                .params(limit).query(Long.class).list();
    }

    private StoredTokens map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        LocalDateTime expires = rs.getObject("access_token_expires_at", LocalDateTime.class);
        String refresh = rs.getString("refresh_token_enc");
        return new StoredTokens(cipher.decrypt(rs.getString("access_token_enc")),
                refresh == null ? null : cipher.decrypt(refresh),
                expires == null ? null : expires.toInstant(ZoneOffset.UTC), rs.getString("scopes"));
    }
}
