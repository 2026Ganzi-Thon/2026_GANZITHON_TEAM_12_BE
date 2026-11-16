package com.comedown.schedule.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WeeklyLoadJpaRepository extends JpaRepository<WeeklyLoadEntity, WeeklyLoadId> {

    @Query("select w from WeeklyLoadEntity w where w.id.userId = :userId "
            + "and w.id.weekStart between :from and :to order by w.id.weekStart")
    List<WeeklyLoadEntity> findBetween(@Param("userId") long userId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** 범위 안 행의 가장 늦은 계산 시각. 행이 없으면 null. */
    @Query("select max(w.computedAt) from WeeklyLoadEntity w where w.id.userId = :userId "
            + "and w.id.weekStart between :from and :to")
    Instant lastComputedAt(@Param("userId") long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from WeeklyLoadEntity w where w.id.userId = :userId")
    int deleteAllByUser(@Param("userId") long userId);
}
