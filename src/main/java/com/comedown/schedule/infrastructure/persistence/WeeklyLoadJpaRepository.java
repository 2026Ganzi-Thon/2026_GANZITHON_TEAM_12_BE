package com.comedown.schedule.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WeeklyLoadJpaRepository extends JpaRepository<WeeklyLoadEntity, WeeklyLoadId> {

    @Query("select w from WeeklyLoadEntity w where w.id.userId = :userId "
            + "and w.id.weekStart between :from and :to order by w.id.weekStart")
    List<WeeklyLoadEntity> findBetween(@Param("userId") long userId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
