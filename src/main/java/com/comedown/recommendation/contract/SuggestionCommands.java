package com.comedown.recommendation.contract;

/**
 * recommendation이 공개하는 제안 생성·사용자 행동 계약. 모든 명령은 로그인 사용자 소유 제안만 다루며
 * 남의 제안 id는 404 NOT_FOUND다. 반복 dismiss/done은 같은 결과, 그 외 잘못된 전이는 409 INVALID_STATE.
 */
public interface SuggestionCommands {

    /** 새 제안을 PROPOSED로 저장한다. 승인 재시도용 등록 키(Google event id)를 이때 만든다. */
    SuggestionView propose(NewSuggestion command);

    /** 건너뛰기/나중에(PROPOSED에서만). */
    SuggestionView dismiss(long userId, long suggestionId);

    /** "했어요"(APPROVED이고 일정 종료 후). doneAt만 채우고 상태 유지. */
    SuggestionView markDone(long userId, long suggestionId);

    /** 만료 시각이 지난 PROPOSED 제안을 EXPIRED로 바꾼다. @return 바꾼 수 */
    int expireDue();
}
