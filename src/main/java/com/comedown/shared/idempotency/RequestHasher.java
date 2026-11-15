package com.comedown.shared.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 요청 본문을 정규화한 JSON(속성·맵 키 정렬)의 SHA-256(소문자 hex 64자)으로 바꾼다.
 * 공백·필드 순서가 달라도 같은 내용이면 같은 해시다. 원본 본문(원신호 등)은 저장하지 않고 해시만 남긴다.
 */
final class RequestHasher {

    private final JsonMapper canonicalMapper;

    RequestHasher(JsonMapper jsonMapper) {
        this.canonicalMapper = jsonMapper.rebuild()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    String hash(Object request) {
        byte[] json = canonicalMapper.writeValueAsBytes(request);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }
}
