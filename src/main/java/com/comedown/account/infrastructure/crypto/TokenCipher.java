package com.comedown.account.infrastructure.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 구글 토큰 암호화(AES-256-GCM). 키는 {@code COMEDOWN_TOKEN_KEY}(Base64로 인코딩한 32바이트)이다.
 * 저장 형식은 Base64(nonce 12바이트 + 암호문·인증 태그)이고 호출마다 새 nonce를 쓴다.
 * 키가 없으면 암호화·복호화를 시도할 때 실패한다(토큰을 평문으로 저장하지 않는다).
 */
@Component
public class TokenCipher {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(@Value("${comedown.token-key:}") String base64Key) {
        this.key = base64Key == null || base64Key.isBlank() ? null : parse(base64Key.trim());
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("토큰 암호화에 실패했습니다.", e);
        }
    }

    public String decrypt(String stored) {
        requireKey();
        try {
            byte[] bytes = Base64.getDecoder().decode(stored);
            if (bytes.length <= NONCE_BYTES) {
                throw new IllegalArgumentException("암호문 형식이 올바르지 않습니다.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, java.util.Arrays.copyOf(bytes, NONCE_BYTES)));
            return new String(cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // 키가 다르거나 암호문이 변조됐다. 내용은 로그에 남기지 않는다.
            throw new IllegalStateException("토큰 복호화에 실패했습니다.", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("COMEDOWN_TOKEN_KEY가 설정되지 않아 토큰을 암호화할 수 없습니다.");
        }
    }

    private static SecretKeySpec parse(String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("COMEDOWN_TOKEN_KEY는 Base64 문자열이어야 합니다.");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("COMEDOWN_TOKEN_KEY는 Base64로 인코딩한 32바이트여야 합니다.");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
