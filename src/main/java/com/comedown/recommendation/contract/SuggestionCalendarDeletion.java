package com.comedown.recommendation.contract;

/**
 * recommendation이 공개하는 캘린더 근거 삭제 계약. 호출하는 쪽의 트랜잭션에 참여한다.
 * <ul>
 *   <li>미승인 PRE_START 제안(APPROVING·APPROVED가 아닌 것)을 지운다.</li>
 *   <li>남는 제안은 일정 참조(related_event_id)만 끊는다. 승인 이력·구글 event id는 유지한다.</li>
 * </ul>
 */
public interface SuggestionCalendarDeletion {

    Removed removeCalendarBasis(long userId);

    /**
     * @param deleted  지운 미승인 PRE_START 제안 수
     * @param detached 일정 참조만 끊은 제안 수
     */
    record Removed(int deleted, int detached) {
    }
}
