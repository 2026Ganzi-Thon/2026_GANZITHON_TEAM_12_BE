package com.comedown.recommendation.contract;

import java.time.Instant;

/**
 * 제안 승인·되돌리기 흐름(experience.suggestions, 일정축)이 쓰는 상태 전이 계약. recommendation이 상태와 등록 키를 소유하고,
 * Google 등록·삭제는 calendar가 한다. 외부 호출은 이 계약의 짧은 트랜잭션들 사이에서 한다(긴 트랜잭션 금지).
 * <pre>
 * 1. beginApproval  (트랜잭션 A: 사용자 잠금 → 겹침 검사 → APPROVING, 등록 키 반환)
 * 2. calendar에 registrationKey를 event id로 등록(트랜잭션 밖). 타임아웃이면 같은 키로 조회해 확인
 * 3. confirmApproved(성공) 또는 markFailed(확정 실패만)  (트랜잭션 B)
 * </pre>
 */
public interface SuggestionApprovalCommands {

    /**
     * 승인 시작. PROPOSED·FAILED → APPROVING. 사용자의 제안 행을 잠가 같은 사용자의 승인을 직렬화하고,
     * 다른 APPROVING·APPROVED 예약과 겹치면 409 SLOT_CONFLICT.
     * 이미 APPROVING·APPROVED면 바꾸지 않고 현재 상태를 돌려준다(같은 승인 재시도: 흐름이 등록 키로 확인).
     *
     * @param start 시간을 바꿀 때만(같은 targetDate·같은 길이·수면 제외는 호출하는 쪽이 검증). null이면 기존 시간
     * @throws com.comedown.shared.error.ApiException DISMISSED·EXPIRED·UNDONE이면 409 SUGGESTION_NOT_PROPOSED
     */
    SuggestionView beginApproval(long userId, long suggestionId, Instant start, Instant end);

    /** Google 등록 확인 후 APPROVED. */
    SuggestionView confirmApproved(long userId, long suggestionId, String googleEventId);

    /** 확정 등록 실패만 FAILED(타임아웃은 부르지 않음). */
    SuggestionView markFailed(long userId, long suggestionId);

    /**
     * 되돌리기 가능 여부 확인(APPROVED이고 했어요 전, 이미 UNDONE이면 그대로). 삭제할 googleEventId를 담아 돌려준다.
     *
     * @throws com.comedown.shared.error.ApiException 그 외 상태는 409 INVALID_STATE
     */
    SuggestionView requireUndoable(long userId, long suggestionId);

    /** Google 일정 삭제(이미 삭제된 404 포함) 후 UNDONE. */
    SuggestionView confirmUndone(long userId, long suggestionId);
}
