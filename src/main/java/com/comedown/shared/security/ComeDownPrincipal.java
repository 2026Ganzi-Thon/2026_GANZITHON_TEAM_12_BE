package com.comedown.shared.security;

/**
 * 로그인 사용자 principal이 구현할 인터페이스.
 * <p>
 * Google 로그인 성공 시 account가 만드는 principal(OAuth2User 구현 등)이 이 인터페이스를 구현한다.
 * shared는 account를 import하지 않고 이 인터페이스로만 사용자 id를 읽는다.
 * <p>
 * 인증 객체의 이름({@code Authentication.getName()})은 users.id 문자열이 되게 한다
 * (예: {@link org.springframework.security.core.AuthenticatedPrincipal} 구현). Spring Session이 이 이름을
 * PRINCIPAL_NAME 색인으로 저장하므로 탈퇴 때 사용자의 모든 세션을 찾아 지울 수 있다.
 */
public interface ComeDownPrincipal {

    /** users.id */
    long userId();
}
