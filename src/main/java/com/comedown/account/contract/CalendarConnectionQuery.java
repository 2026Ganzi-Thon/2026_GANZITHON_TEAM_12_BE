package com.comedown.account.contract;

/**
 * 캘린더 연결 여부 읽기 계약. 자격정보(oauth_tokens)는 account가 소유하므로 판단 기준도 account가 갖는다.
 * 토큰 값은 노출하지 않는다.
 */
public interface CalendarConnectionQuery {

    /** 사용자가 Google 캘린더 권한을 허용해 연결된 상태인지. */
    boolean isConnected(long userId);
}
