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
import org.springframework.data.jpa.repository.Modifying;
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

    /** 해당 종류 중 지정한 상태가 아닌 제안을 지운다(건강 기록 삭제: 미승인 회복 추천). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Suggestion s WHERE s.userId = :userId AND s.type = :type AND s.status NOT IN :kept")
    int deleteByTypeExceptStatuses(@Param("userId") long userId, @Param("type") SuggestionType type,
            @Param("kept") Collection<SuggestionStatus> kept);

    /** 남은 회복 추천의 건강 근거(판단·사유·문구)를 지운다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Suggestion s
               SET s.judgmentState = NULL, s.reasonCode = :reasonCode, s.message = NULL,
                   s.aiGenerated = FALSE, s.fallback = FALSE, s.updatedAt = :now
             WHERE s.userId = :userId AND s.type = :type
            """)
    int stripRecoveryBasis(@Param("userId") long userId, @Param("type") SuggestionType type,
            @Param("reasonCode") String reasonCode, @Param("now") Instant now);

    /** 그 외 제안(PRE_START)의 2축 판단(회복 상태가 들어감)을 비운다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Suggestion s SET s.judgmentState = NULL, s.updatedAt = :now
             WHERE s.userId = :userId AND s.type <> :type AND s.judgmentState IS NOT NULL
            """)
    int clearJudgmentExceptType(@Param("userId") long userId, @Param("type") SuggestionType type,
            @Param("now") Instant now);

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
