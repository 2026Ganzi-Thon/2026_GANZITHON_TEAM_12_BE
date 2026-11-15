package com.comedown.safety.infrastructure.persistence;

import com.comedown.safety.domain.SafetyAlert;
import com.comedown.safety.domain.SafetyTrigger;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SafetyAlertRepository extends JpaRepository<SafetyAlert, Long> {

    /** 조건이 아직 풀리지 않은 안내. */
    List<SafetyAlert> findByUserIdAndTriggerTypeAndResolvedAtIsNull(long userId, SafetyTrigger type);

    List<SafetyAlert> findByUserIdOrderByTriggeredAtDescIdDesc(long userId);

    Optional<SafetyAlert> findByIdAndUserId(long id, long userId);
}
