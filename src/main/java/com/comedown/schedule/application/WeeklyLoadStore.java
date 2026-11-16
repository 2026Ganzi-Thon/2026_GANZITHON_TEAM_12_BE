package com.comedown.schedule.application;

import com.comedown.schedule.domain.WeekLoad;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** weekly_loads 저장 포트. schedule만 쓴다. */
public interface WeeklyLoadStore {

    Optional<WeekLoad> find(long userId, LocalDate weekStart);

    /** weekStart가 {@code [from, toInclusive]}에 드는 행을 주 시작일 오름차순으로. */
    List<WeekLoad> findBetween(long userId, LocalDate from, LocalDate toInclusive);

    /** weekStart가 {@code [from, toInclusive]}에 드는 행 중 가장 늦은 계산 시각. 행이 없으면 비어 있다. */
    Optional<Instant> lastComputedAt(long userId, LocalDate from, LocalDate toInclusive);

    /** 같은 (userId, weekStart) 행이 있으면 갱신한다. */
    void save(long userId, WeekLoad load, Instant computedAt);

    /** 사용자의 모든 행을 지운다. @return 지운 행 수 */
    int deleteAll(long userId);
}
