package com.comedown.account.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes());

    @Test
    void 암호화한_값은_복호화하면_원문과_같고_평문이_드러나지_않는다() {
        TokenCipher cipher = new TokenCipher(KEY);

        String stored = cipher.encrypt("ya29.access-token");

        assertThat(stored).doesNotContain("ya29").isNotEqualTo("ya29.access-token");
        assertThat(cipher.decrypt(stored)).isEqualTo("ya29.access-token");
    }

    @Test
    void 같은_원문도_호출마다_다른_암호문이_나온다() {
        TokenCipher cipher = new TokenCipher(KEY);

        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void 다른_키로는_복호화할_수_없다() {
        String stored = new TokenCipher(KEY).encrypt("secret");

        assertThatThrownBy(() -> new TokenCipher(OTHER_KEY).decrypt(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    void 변조된_암호문은_복호화에_실패한다() {
        TokenCipher cipher = new TokenCipher(KEY);
        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("secret"));
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키가_없으면_평문으로_저장하지_않고_실패한다() {
        TokenCipher cipher = new TokenCipher("");

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("secret")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키_형식이_잘못되면_시작할_때_실패한다() {
        assertThatThrownBy(() -> new TokenCipher("not-base64!!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}
