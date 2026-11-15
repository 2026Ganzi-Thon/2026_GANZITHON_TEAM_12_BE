package com.comedown.subscription.contract;

import java.time.Instant;

/** 이용 자격 쓰기 계약. subscriptions 쓰기는 subscription만 한다. */
public interface SubscriptionCommands {

    /**
     * 무료 체험(28일)을 시작한다. 이미 시작했으면 바꾸지 않는다(재시도 안전).
     * 호출하는 쪽의 트랜잭션에 참여한다.
     */
    void startTrial(long userId, Instant startedAt);
}
