package com.comedown.account.contract;

import java.util.List;

/**
 * 구글 접근 권한(토큰) 철회 계약. 토큰은 account 밖으로 나가지 않는다.
 * 철회 요청 표시가 되면 연결은 즉시 끊긴 것으로 보이고, 구글 철회가 끝나야 토큰이 지워진다(실패하면 재시도).
 */
public interface GoogleAccessRevocation {

    /** 철회를 요청해 연결을 숨긴다(토큰 행이 없으면 아무 일도 하지 않는다). 호출하는 쪽의 트랜잭션에 참여한다. */
    void requestRevoke(long userId);

    /**
     * 요청된 철회를 구글에 보내고 끝나면 토큰을 지운다. DB 트랜잭션 밖에서 호출한다.
     *
     * @return 끝났으면(또는 철회할 것이 없으면) true, 구글 호출이 실패해 나중에 다시 해야 하면 false
     */
    boolean revokePending(long userId);

    /** 철회가 끝나지 않은 사용자(재시도 대상). */
    List<Long> pendingUsers(int limit);
}
