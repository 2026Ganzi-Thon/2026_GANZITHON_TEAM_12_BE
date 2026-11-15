package com.comedown.calendar.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 일정 제목의 식별값(title_hash). 제목 원문은 저장하지 않고 "같은 제목 일정에 적용"에만 쓴다.
 * HMAC-SHA256(서버 키, "title-hash-v1:" + userId + ":" + 정규화한 제목)의 16진 문자열이다.
 * 키는 토큰 암호화 키({@code COMEDOWN_TOKEN_KEY})를 쓴다. 키가 없으면 해시를 만들지 않는다(TITLE 범위 사용 불가).
 */
@Component
public class TitleHasher {

    private final SecretKeySpec key;

    public TitleHasher(@Value("${comedown.token-key:}") String base64Key) {
        this.key = base64Key == null || base64Key.isBlank()
                ? null : new SecretKeySpec(Base64.getDecoder().decode(base64Key.trim()), "HmacSHA256");
    }

    /** 제목이 없거나 키가 없으면 null. */
    public String hash(long userId, String title) {
        if (key == null || title == null || title.isBlank()) {
            return null;
        }
        String normalized = title.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(
                    mac.doFinal(("title-hash-v1:" + userId + ":" + normalized).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("제목 해시 계산에 실패했습니다.", e);
        }
    }
}
