package com.comedown.account.contract;

/**
 * 한 요청 안에서만 쓰는 구글 캘린더 호출용 자격. 로그·DTO·응답에 넣지 않는다.
 */
public record CalendarCredential(String accessToken) {

    @Override
    public String toString() {
        return "CalendarCredential[***]";
    }
}
