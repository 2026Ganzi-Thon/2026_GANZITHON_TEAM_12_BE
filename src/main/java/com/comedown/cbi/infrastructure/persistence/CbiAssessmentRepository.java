package com.comedown.cbi.infrastructure.persistence;

import com.comedown.cbi.domain.CbiAssessment;
import com.comedown.cbi.domain.CbiRound;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CbiAssessmentRepository extends JpaRepository<CbiAssessment, Long> {

    boolean existsByUserIdAndRound(long userId, CbiRound round);

    Optional<CbiAssessment> findByUserIdAndRound(long userId, CbiRound round);

    List<CbiAssessment> findByUserIdOrderByTakenAtDescIdDesc(long userId);
}
