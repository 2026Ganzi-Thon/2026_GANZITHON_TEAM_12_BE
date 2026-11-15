package com.comedown.subscription.contract;

import java.util.Optional;

/** 이용 자격 읽기 계약. 체험을 시작하기 전(온보딩 중)에는 비어 있다. */
public interface SubscriptionQuery {

    Optional<PlanView> find(long userId);
}
