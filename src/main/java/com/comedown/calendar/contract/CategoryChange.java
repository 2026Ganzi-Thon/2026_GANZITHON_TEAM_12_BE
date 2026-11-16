package com.comedown.calendar.contract;

/**
 * 분류 직접 수정 요청. {@code category}·{@code scope}는 이름으로 받고 calendar가 검증한다.
 *
 * @param burden     부담 정도 1~3. 보내지 않으면 null(정하지 않음)
 * @param adjustable 미리 시작할 수 있는 일인지. 보내지 않으면 null(카테고리 기본값을 쓴다)
 * @param scope      THIS / SERIES / TITLE
 * @param customCategory "기타"(OTHER)를 고르고 직접 입력한 카테고리 이름(1~20자). 없으면 null
 */
public record CategoryChange(String category, Integer burden, Boolean adjustable, boolean deadline, String scope,
        String customCategory) {
}
