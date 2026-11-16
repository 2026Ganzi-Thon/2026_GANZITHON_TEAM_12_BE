package com.comedown.schedule.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 주간 부하 재계산·단건 조회 계약. weekly_loads 쓰기는 schedule만 한다. */
public interface WeekLoads {

    /** 주(월요일 날짜)들을 오래된 순으로 다시 계산해 저장하고 결과를 돌려준다. */
    List<WeekLoadView> recalculate(long userId, Collection<LocalDate> weekStarts);

    /** 저장된 한 주의 부하. 계산된 적 없으면 비어 있다. */
    Optional<WeekLoadView> find(long userId, LocalDate weekStart);

    /** {@code from}이 속한 주부터 {@code count}주의 저장된 부하(계산된 적 없는 주는 빠진다). */
    List<WeekLoadView> range(long userId, LocalDate from, int count);

    /**
     * {@code from}이 속한 주부터 {@code count}주의 저장된 부하 중 가장 늦게 계산한 시각. 저장된 주가 없으면 비어 있다.
     * 동기화는 일정이 0건이어도 이 주들의 부하를 계산해 저장하므로, 일정 행이 하나도 없는 사용자도 분석한 적이 있는지 알 수 있다.
     */
    Optional<Instant> lastComputedAt(long userId, LocalDate from, int count);
}
