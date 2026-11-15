package com.comedown.cbi.infrastructure.persistence;

import com.comedown.cbi.domain.CbiAssessment;
import com.comedown.cbi.domain.CbiRound;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CbiAssessmentRepository extends JpaRepository<CbiAssessment, Long> {

    boolean existsByUserIdAndRound(long userId, CbiRound round);

    Optional<CbiAssessment> findByUserIdAndRound(long userId, CbiRound round);

    List<CbiAssessment> findByUserIdOrderByTakenAtDescIdDesc(long userId);

    /** 문항별 답(cbi_answers)은 DB FK ON DELETE CASCADE로 함께 지워진다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CbiAssessment a WHERE a.userId = :userId")
    int deleteAllOfUser(@Param("userId") long userId);
}
