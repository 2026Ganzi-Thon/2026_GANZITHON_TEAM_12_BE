package com.comedown.shared.config;

/**
 * 특정 API의 요청 본문 크기 상한. 기능의 설정 클래스가 이 타입의 빈을 등록하면 {@link RequestBodyLimitFilter}가 적용한다.
 * 예: 측정 저장 {@code new RequestBodyLimit("/api/hrv/measurements", 512 * 1024)}
 *
 * @param path     요청 경로(쿼리 제외, 정확히 일치)
 * @param maxBytes 허용하는 최대 바이트 수
 */
public record RequestBodyLimit(String path, long maxBytes) {

    public RequestBodyLimit {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path는 /로 시작해야 합니다: " + path);
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes는 양수여야 합니다: " + maxBytes);
        }
    }
}
