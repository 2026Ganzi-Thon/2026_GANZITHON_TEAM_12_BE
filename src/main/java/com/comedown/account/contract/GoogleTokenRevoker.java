package com.comedown.account.contract;

/**
 * 구글 토큰 철회 포트(전송은 integration.google이 구현). 이미 철회된 토큰은 성공으로 본다.
 * 실패는 {@link com.comedown.shared.error.ExternalServiceException}으로 알린다.
 */
public interface GoogleTokenRevoker {

    void revoke(String token);
}
