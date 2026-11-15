package com.comedown.calendar.contract;

/**
 * 분류 직접 수정 요청. {@code category}·{@code scope}는 이름으로 받고 calendar가 검증한다.
 *
 * @param scope THIS / SERIES / TITLE
 */
public record CategoryChange(String category, int burden, boolean adjustable, boolean deadline, String scope) {
}
