package com.comedown.recommendation.infrastructure.persistence;

import com.comedown.recommendation.domain.Suggestion;
import com.comedown.recommendation.domain.SuggestionStatus;
import com.comedown.recommendation.domain.SuggestionType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SuggestionRepository extends JpaRepository<Suggestion, Long> {

    Optional<Suggestion> findByIdAndUserId(long id, long userId);

    /** 목록 조회. 조건이 null이면 거르지 않는다. 시작 시각 순. */
    @Query("""
            SELECT s FROM Suggestion s
             WHERE s.userId = :userId
               AND (:type IS NULL OR s.type = :type)
               AND (:status IS NULL OR s.status = :status)
               AND (:targetDate IS NULL OR s.targetDate = :targetDate)
             ORDER BY s.startAt ASC, s.id ASC
            """)
    List<Suggestion> search(@Param("userId") long userId, @Param("type") SuggestionType type,
            @Param("status") SuggestionStatus status, @Param("targetDate") LocalDate targetDate);

    /**
     * 사용자의 제안 행을 모두 잠근다. 같은 사용자의 승인 시작을 직렬화해 겹친 시간 예약을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Suggestion s WHERE s.userId = :userId")
    List<Suggestion> lockAllOfUser(@Param("userId") long userId);

    /** 만료 시각이 지난 PROPOSED 제안. */
    @Query("SELECT s FROM Suggestion s WHERE s.status = :status AND s.expiresAt <= :now")
    List<Suggestion> findDue(@Param("status") SuggestionStatus status, @Param("now") Instant now);

    /** 같은 사용자의 [start, end)와 겹치는 다른 예약(APPROVING·APPROVED). */
    @Query("""
            SELECT s FROM Suggestion s
             WHERE s.userId = :userId AND s.id <> :excludeId AND s.status IN :statuses
               AND s.startAt < :end AND s.endAt > :start
            """)
    List<Suggestion> findOverlapping(@Param("userId") long userId, @Param("excludeId") long excludeId,
            @Param("statuses") Collection<SuggestionStatus> statuses, @Param("start") Instant start,
            @Param("end") Instant end);
}
