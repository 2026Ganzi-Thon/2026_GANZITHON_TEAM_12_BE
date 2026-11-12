package com.comedown.shared.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * 공통 필터 체인에 기능별 보안 설정을 덧붙이는 확장 지점.
 * <p>
 * 예: account가 Google OAuth2 로그인({@code http.oauth2Login(...)})과 성공/실패 핸들러를 이 빈으로 등록한다.
 * shared.security는 기능 패키지를 import하지 않고 이 인터페이스의 빈 목록만 적용한다.
 */
@FunctionalInterface
public interface SecurityFilterChainContributor {

    void contribute(HttpSecurity http) throws Exception;
}
