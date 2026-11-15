package com.comedown.checkin.contract;

/**
 * checkin이 공개하는 컨디션 저장 계약.
 */
public interface ConditionCommands {

    /**
     * 그날 아침 체크에 컨디션·교란 요인을 저장한다. 체크가 없으면 만든다(측정 없이도 저장 가능).
     * 다시 저장하면 값을 바꾸고, 완료 시각(completed_at)은 처음 완료한 시각을 유지한다.
     *
     * @return 저장한 아침 체크 id
     */
    long saveCondition(SaveCondition command);
}
