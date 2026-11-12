package com.comedown.recovery.infrastructure.persistence;

import com.comedown.recovery.domain.HrvDailyState;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HrvDailyStateRepository extends JpaRepository<HrvDailyState, HrvDailyState.Key> {

    Optional<HrvDailyState> findByUserIdAndStateDate(long userId, LocalDate stateDate);

    List<HrvDailyState> findByUserIdAndStateDateBetweenOrderByStateDateAsc(long userId, LocalDate from, LocalDate to);

    /** from 이후 결과를 지운다. 범위 삭제가 잠금을 잡아 같은 사용자의 동시 재계산을 직렬화한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM HrvDailyState s WHERE s.userId = :userId AND s.stateDate >= :from")
    int deleteFrom(@Param("userId") long userId, @Param("from") LocalDate from);
}
