package com.comedown.account.contract;

import java.util.Optional;

/**
 * account가 다른 기능(experience)에 공개하는 읽기 전용 계정 상태 계약.
 * 동의·측정 켜짐·온보딩 상태의 판단 기준은 account가 소유하고, 호출하는 쪽은 결과만 사용한다.
 */
public interface AccountAccessQuery {

    /** 사용자가 없으면 비어 있다. */
    Optional<HealthDataAccess> findHealthDataAccess(long userId);

    /**
     * 캘린더 이용 동의(CALENDAR)가 유효한지. 가장 최근 동의 행이 철회되지 않았으면 유효하다.
     * 프로필 단계에서 필수 동의로 기록하므로, 프로필 단계를 마치기 전이거나 사용자가 없으면 false다.
     */
    boolean calendarConsented(long userId);

    /** 화면 표시용 닉네임. 사용자가 없거나 닉네임을 정하기 전이면 비어 있다. */
    Optional<String> findNickname(long userId);
}
