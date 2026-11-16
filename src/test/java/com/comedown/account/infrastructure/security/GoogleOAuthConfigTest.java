package com.comedown.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.comedown.account.infrastructure.crypto.TokenCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** GOOGLE_CLIENT_ID가 있어 Google 로그인이 켜질 때, 함께 있어야 하는 설정이 없으면 시작하지 않는다. */
class GoogleOAuthConfigTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void 토큰_키와_클라이언트_비밀_값이_있으면_만들어진다() {
        assertThatCode(() -> new GoogleOAuthConfig(new TokenCipher(KEY), "test-client-secret"))
                .doesNotThrowAnyException();
    }

    @Test
    void 클라이언트_비밀_값이_비어_있으면_시작할_때_실패한다() {
        for (String secret : new String[] {"", "   ", null}) {
            assertThatThrownBy(() -> new GoogleOAuthConfig(new TokenCipher(KEY), secret))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("GOOGLE_CLIENT_SECRET");
        }
    }

    @Test
    void 토큰_키가_없으면_시작할_때_실패한다() {
        assertThatThrownBy(() -> new GoogleOAuthConfig(new TokenCipher(""), "test-client-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMEDOWN_TOKEN_KEY");
    }
}
