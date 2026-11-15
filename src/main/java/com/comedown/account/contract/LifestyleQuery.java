package com.comedown.account.contract;

import java.time.LocalTime;

/** 생활 정보 읽기 계약(제안이 빈 시간을 찾을 때 수면 시간대를 쓴다). */
public interface LifestyleQuery {

    /** @param sleepStart 수면 시작 시각, {@code sleepEnd} 수면 종료 시각(자정을 넘길 수 있다). 저장 전이면 기본값 23:00~07:00 */
    record SleepWindow(LocalTime sleepStart, LocalTime sleepEnd) {
    }

    SleepWindow sleepWindow(long userId);
}
