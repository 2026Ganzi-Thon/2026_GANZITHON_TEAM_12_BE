package com.comedown.account.contract;

/**
 * 구글 캘린더 호출에 쓸 자격을 account가 내어 주는 계약. 토큰 복호화·만료 시 갱신은 account가 맡는다.
 */
public interface CalendarCredentialProvider {

    /**
     * 유효한 access token을 돌려준다(만료됐으면 갱신해 저장한 뒤).
     *
     * @throws com.comedown.shared.error.ApiException CALENDAR_NOT_CONNECTED(토큰 없음), CALENDAR_SCOPE_MISSING
     *         (캘린더 범위 없음), CALENDAR_REAUTH_REQUIRED(갱신 불가·철회), CALENDAR_API_ERROR(구글 장애)
     */
    CalendarCredential credentialFor(long userId);
}
