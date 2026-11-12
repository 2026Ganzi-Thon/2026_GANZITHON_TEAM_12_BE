package com.comedown.shared.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/auth/csrf} → 204. XSRF-TOKEN 쿠키만 발급한다.
 * 프론트는 이후 POST/PUT/PATCH/DELETE 요청에 쿠키 값을 X-XSRF-TOKEN 헤더로 보낸다.
 */
@RestController
public class CsrfController {

    @GetMapping("/api/auth/csrf")
    public ResponseEntity<Void> csrf(CsrfToken csrfToken) {
        // 지연 로딩된 토큰을 읽어야 쿠키가 응답에 기록된다.
        csrfToken.getToken();
        return ResponseEntity.noContent().build();
    }
}
