package com.comedown.schedule.contract;

import java.time.LocalDate;

/** 임의 기간의 일정 요약 계약. 저장하지 않고 일정 원본으로 계산한다. */
public interface PeriodLoadQuery {

    /** {@code [from, toInclusive]} 기간 요약. */
    PeriodLoad summarize(long userId, LocalDate from, LocalDate toInclusive);
}
