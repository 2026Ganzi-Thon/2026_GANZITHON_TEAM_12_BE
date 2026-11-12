package com.comedown.recommendation.domain;

/**
 * 제안 상태(API 명세 5절).
 * PROPOSED → APPROVING → APPROVED, PROPOSED → DISMISSED/EXPIRED, 확정 실패만 APPROVING → FAILED(재시도는 FAILED → APPROVING),
 * APPROVED → UNDONE(되돌리기).
 */
public enum SuggestionStatus {
    PROPOSED,
    APPROVING,
    APPROVED,
    DISMISSED,
    EXPIRED,
    FAILED,
    UNDONE
}
