package com.comedown.recommendation.contract;

/**
 * recommendation이 공개하는 건강 기록 삭제 계약. 호출하는 쪽의 트랜잭션에 참여한다.
 * <ul>
 *   <li>회복 기반 미승인 추천(RECOVERY 중 APPROVING·APPROVED가 아닌 것)을 지운다.</li>
 *   <li>남는 제안(승인된 RECOVERY·진행 중 승인·PRE_START)에서 건강 근거를 지운다:
 *       판단(judgment_state)을 비우고, RECOVERY는 사유를 {@value #HEALTH_DATA_DELETED}로 바꾸고 문구를 비운다.</li>
 *   <li>캘린더에 이미 등록된 일정과 승인 이력(상태·시간·제목·Google event id)은 유지한다.</li>
 * </ul>
 */
public interface SuggestionDataDeletion {

    /** 건강 근거를 지운 제안의 reason_code. */
    String HEALTH_DATA_DELETED = "HEALTH_DATA_DELETED";

    Removed removeHealthBasis(long userId);

    /**
     * @param deleted  지운 미승인 회복 추천 수
     * @param stripped 건강 근거만 지운 제안 수
     */
    record Removed(int deleted, int stripped) {
    }
}
