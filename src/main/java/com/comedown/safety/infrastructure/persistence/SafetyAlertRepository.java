package com.comedown.safety.infrastructure.persistence;

import com.comedown.safety.domain.SafetyAlert;
import com.comedown.safety.domain.SafetyTrigger;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SafetyAlertRepository extends JpaRepository<SafetyAlert, Long> {

    /** 조건이 아직 풀리지 않은 안내. */
    List<SafetyAlert> findByUserIdAndTriggerTypeAndResolvedAtIsNull(long userId, SafetyTrigger type);

    List<SafetyAlert> findByUserIdOrderByTriggeredAtDescIdDesc(long userId);

    Optional<SafetyAlert> findByIdAndUserId(long id, long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM SafetyAlert a WHERE a.userId = :userId")
    int deleteAllOfUser(@Param("userId") long userId);
}
