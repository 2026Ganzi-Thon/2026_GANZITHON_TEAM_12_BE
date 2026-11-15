package com.comedown.account.infrastructure.persistence;

import com.comedown.account.infrastructure.crypto.TokenCipher;
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

    private final JdbcClient jdbc;
    private final TokenCipher cipher;

    public GoogleTokenReader(JdbcClient jdbc, TokenCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public Optional<StoredTokens> find(long userId) {
        return jdbc.sql("""
                        SELECT access_token_enc, refresh_token_enc, access_token_expires_at, scopes
                          FROM oauth_tokens WHERE user_id = ?
                        """)
                .params(userId)
                .query((rs, n) -> {
                    LocalDateTime expires = rs.getObject("access_token_expires_at", LocalDateTime.class);
                    String refresh = rs.getString("refresh_token_enc");
                    return new StoredTokens(cipher.decrypt(rs.getString("access_token_enc")),
                            refresh == null ? null : cipher.decrypt(refresh),
                            expires == null ? null : expires.toInstant(ZoneOffset.UTC),
                            rs.getString("scopes"));
                })
                .optional();
    }
}
