package com.comedown.account.contract;

import java.time.Instant;

/**
 * refresh token으로 새 access token을 받는 포트. 구글 전송은 integration.google이 구현한다.
 * 실패는 {@link com.comedown.shared.error.ExternalServiceException}으로 알린다.
 */
public interface GoogleTokenRefresher {

    /** 갱신된 access token. */
    record RefreshedToken(String accessToken, Instant issuedAt, Instant expiresAt) {
    }

    RefreshedToken refresh(String refreshToken, Instant now);
}
