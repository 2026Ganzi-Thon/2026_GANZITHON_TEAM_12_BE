package com.comedown.account.contract;

import java.util.Optional;

/**
 * account가 다른 기능(experience)에 공개하는 읽기 전용 계정 상태 계약.
 * 동의·측정 켜짐·온보딩 상태의 판단 기준은 account가 소유하고, 호출하는 쪽은 결과만 사용한다.
 */
public interface AccountAccessQuery {

    /** 사용자가 없으면 비어 있다. */
    Optional<HealthDataAccess> findHealthDataAccess(long userId);
}
