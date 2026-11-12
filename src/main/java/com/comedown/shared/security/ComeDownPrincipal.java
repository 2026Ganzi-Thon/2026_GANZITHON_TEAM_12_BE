package com.comedown.shared.security;

/**
 * 로그인 사용자 principal이 구현할 인터페이스.
 * <p>
 * Google 로그인 성공 시 account가 만드는 principal(OAuth2User 구현 등)이 이 인터페이스를 구현한다.
 * shared는 account를 import하지 않고 이 인터페이스로만 사용자 id를 읽는다.
 */
public interface ComeDownPrincipal {

    /** users.id */
    long userId();
}
