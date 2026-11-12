package com.comedown.shared.security;

/**
 * 인증된 현재 사용자. Controller 메서드 파라미터로 선언하면 {@link CurrentUserArgumentResolver}가 채운다.
 * <pre>{@code
 * @GetMapping("/api/me")
 * MeResponse me(CurrentUser user) { ... user.id() ... }
 * }</pre>
 * 요청 본문의 userId는 신뢰하지 않고 항상 이 값을 사용한다.
 */
public record CurrentUser(long id) {
}
