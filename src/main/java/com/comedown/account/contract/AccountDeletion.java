package com.comedown.account.contract;

import java.util.List;

/** 탈퇴 상태 전이 계약. 사용자 행의 쓰기는 account만 한다. */
public interface AccountDeletion {

    /** 사용자 행을 잠가 같은 사용자의 다른 쓰기 흐름과 직렬화한다. 호출하는 쪽의 트랜잭션에 참여한다. */
    void lock(long userId);

    /** 탈퇴 진행 중(DELETING)으로 표시한다(이미 표시됐으면 시각을 바꾸지 않는다). */
    void markDeleting(long userId);

    /** 탈퇴 진행 중인 사용자(재시도 대상). */
    List<Long> deletingUsers(int limit);

    /** 사용자 행을 물리 삭제한다(FK CASCADE로 소유 데이터가 함께 지워진다). 참조 해제·세션 정리를 마친 뒤에 호출한다. */
    void deleteUser(long userId);
}
