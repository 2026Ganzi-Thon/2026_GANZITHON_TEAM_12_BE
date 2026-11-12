package com.comedown.recovery.contract;

import java.time.LocalDate;

/**
 * recovery가 공개하는 회복 재계산 계약(recovery-v1).
 */
public interface RecoveryCommands {

    /**
     * 사용자의 아침 체크 기록을 처음부터 재생해 from ~ through 날짜의 회복 상태를 다시 저장한다.
     * from 이후에 기록이 없어진 날의 행은 지운다. 호출하는 쪽의 트랜잭션 안에서 실행된다.
     *
     * @param from    바뀐 기록의 날짜(재측정·컨디션 변경·삭제한 날)
     * @param through 계산 마지막 날짜(보통 오늘)
     */
    void recalculate(long userId, LocalDate from, LocalDate through);
}
