package com.comedown.checkin.contract;

/**
 * checkin이 공개하는 건강 기록 삭제 계약(건강 기록 전체 삭제·건강정보 동의 철회 + 삭제).
 * 조율은 experience.accountlifecycle이 하며, 호출하는 쪽의 트랜잭션에 참여한다.
 */
public interface CheckinDataDeletion {

    /**
     * 측정(hrv_measurements)을 모두 지우고, 아침 체크의 건강 필드(컨디션·음주·아픔·카페인)와 완료 시각을 비운다.
     * 호흡 완료처럼 건강정보가 아닌 기록이 남은 체크는 행을 유지하고, 남을 것이 없는 체크는 지운다.
     */
    Erased eraseHealthData(long userId);

    /**
     * @param measurements      지운 측정 수
     * @param clearedChecks     건강 필드만 비우고 남긴 체크 수(호흡 기록 있음)
     * @param deletedChecks     통째로 지운 체크 수
     */
    record Erased(int measurements, int clearedChecks, int deletedChecks) {
    }
}
