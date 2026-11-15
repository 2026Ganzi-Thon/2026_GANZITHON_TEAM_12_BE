package com.comedown.experience.morning.application;

import com.comedown.checkin.contract.MeasurementCommands;
import com.comedown.checkin.contract.RecordCameraMeasurement;
import com.comedown.checkin.contract.RecordManualMeasurement;
import com.comedown.checkin.contract.RecordedMeasurement;
import com.comedown.recovery.contract.RecoveryCommands;
import com.comedown.shared.idempotency.IdempotencyService;
import com.comedown.shared.idempotency.IdempotentResponse;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 측정 저장 흐름: 재시도 키로 보호한 한 트랜잭션에서 checkin 저장 → 그 날짜부터 회복 재계산 → 응답 조합.
 * 컨디션 저장 뒤의 재측정도 같은 체크에 연결되고, 그날 이후 회복 상태가 다시 계산된다.
 * <p>
 * 아직 하지 않는 일: 미승인 RECOVERY 추천 재계산(추천 구현 후 연결).
 */
@Service
public class MeasurementSaveFlow {

    /** 건강 기록 삭제 때 {@code hrv-} 접두어로 재시도 응답 캐시도 지운다. */
    public static final String OPERATION = "hrv-measurement:create";

    private final MeasurementCommands measurementCommands;
    private final RecoveryCommands recoveryCommands;
    private final IdempotencyService idempotency;
    private final ServiceTime serviceTime;

    public MeasurementSaveFlow(MeasurementCommands measurementCommands, RecoveryCommands recoveryCommands,
            IdempotencyService idempotency, ServiceTime serviceTime) {
        this.measurementCommands = measurementCommands;
        this.recoveryCommands = recoveryCommands;
        this.idempotency = idempotency;
        this.serviceTime = serviceTime;
    }

    /**
     * @param request 재시도 키 비교용 원 요청(해시만 저장)
     */
    public IdempotentResponse<MeasurementSaveResult> save(long userId, LocalDate onboardingDate, UUID idempotencyKey,
            Object request, ValidatedMeasurement measurement) {
        return idempotency.execute(userId, OPERATION, idempotencyKey, request, MeasurementSaveResult.class, () -> {
            RecordedMeasurement recorded = record(userId, onboardingDate, measurement);
            recoveryCommands.recalculate(userId, recorded.checkDate(), serviceTime.today());
            return IdempotentResponse.of(201, toResult(recorded));
        });
    }

    private RecordedMeasurement record(long userId, LocalDate onboardingDate, ValidatedMeasurement measurement) {
        return switch (measurement) {
            case ValidatedMeasurement.Camera c -> measurementCommands.recordCamera(new RecordCameraMeasurement(
                    userId, onboardingDate, c.startedAt(), c.torch(), c.timeSource(), c.t(), c.r(), c.g()));
            case ValidatedMeasurement.Manual m -> measurementCommands.recordManual(new RecordManualMeasurement(
                    userId, onboardingDate, m.startedAt(), m.rmssdMs()));
        };
    }

    private static MeasurementSaveResult toResult(RecordedMeasurement recorded) {
        return new MeasurementSaveResult(
                new MeasurementSaveResult.Measurement(
                        recorded.measurementId(),
                        recorded.qualityLevel(),
                        "RETRY".equals(recorded.qualityLevel()),
                        recorded.retryReason(),
                        recorded.rmssdMs(),
                        recorded.meanHrBpm()),
                recorded.morningCheckId());
    }
}
