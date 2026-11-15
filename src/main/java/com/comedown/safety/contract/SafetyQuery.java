package com.comedown.safety.contract;

import java.util.List;

/** safety가 공개하는 안전 안내 읽기 계약. */
public interface SafetyQuery {

    /** 조건이 아직 풀리지 않은 안내(최신순). 닫은 안내도 포함한다. */
    List<SafetyAlertView> findOpenAlerts(long userId);

    /** 지금 띄울 안내가 있는지(열려 있고 닫지 않은 안내). */
    default boolean shouldShow(long userId) {
        return findOpenAlerts(userId).stream().anyMatch(a -> !a.acknowledged());
    }
}
