package com.comedown.checkin.infrastructure.persistence;

import com.comedown.checkin.domain.MorningCheck;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MorningCheckRepository extends JpaRepository<MorningCheck, Long> {

    /**
     * 그날 체크가 없으면 만든다. 이미 있으면 아무것도 바꾸지 않는다(day_index 스냅샷 유지).
     * UK(user_id, check_date) 경합에서도 실패하지 않도록 ON DUPLICATE KEY를 쓴다.
     *
     * @param createdAtUtc UTC 기준 생성 시각
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO morning_checks (user_id, check_date, day_index, created_at)
            VALUES (:userId, :checkDate, :dayIndex, :createdAt)
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId, @Param("checkDate") LocalDate checkDate,
            @Param("dayIndex") int dayIndex, @Param("createdAt") LocalDateTime createdAtUtc);

    /** 최신 커밋 값을 잠금 읽기로 가져온다(동시 생성 직후에도 보이도록). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MorningCheck c WHERE c.userId = :userId AND c.checkDate = :checkDate")
    Optional<MorningCheck> findForUpdate(@Param("userId") long userId, @Param("checkDate") LocalDate checkDate);
}
