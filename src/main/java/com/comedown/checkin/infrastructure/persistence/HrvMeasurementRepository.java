package com.comedown.checkin.infrastructure.persistence;

import com.comedown.checkin.domain.HrvMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HrvMeasurementRepository extends JpaRepository<HrvMeasurement, Long> {
}
