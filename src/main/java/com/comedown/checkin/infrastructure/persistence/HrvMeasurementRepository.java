package com.comedown.checkin.infrastructure.persistence;

import com.comedown.checkin.domain.HrvMeasurement;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HrvMeasurementRepository extends JpaRepository<HrvMeasurement, Long> {

    /** 사용자의 해당 체크들에 연결된 측정(측정 시작 시각 순). */
    List<HrvMeasurement> findByUserIdAndMorningCheckIdInOrderByMeasuredAtAscIdAsc(
            long userId, Collection<Long> morningCheckIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM HrvMeasurement m WHERE m.userId = :userId")
    int deleteAllOfUser(@Param("userId") long userId);
}
