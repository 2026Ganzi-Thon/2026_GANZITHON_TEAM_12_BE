package com.comedown.account.contract;

/** 사용자의 로그인 세션 관리 계약(서버 세션 저장소). */
public interface UserSessions {

    /** 사용자의 모든 세션을 지운다. @return 지운 세션 수 */
    int revokeAll(long userId);
}
