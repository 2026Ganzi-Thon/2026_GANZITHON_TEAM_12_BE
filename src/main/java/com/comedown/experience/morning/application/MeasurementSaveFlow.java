package com.comedown.experience.morning.application;

import com.comedown.checkin.contract.MeasurementCommands;
import com.comedown.checkin.contract.RecordCameraMeasurement;
import com.comedown.checkin.contract.RecordManualMeasurement;
import com.comedown.checkin.contract.RecordedMeasurement;
import com.comedown.experience.suggestions.application.RecoverySuggestionFlow;
import com.comedown.recovery.contract.RecoveryCommands;
import com.comedown.shared.idempotency.IdempotencyService;
import com.comedown.shared.idempotency.IdempotentResponse;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 측정 저장 흐름: 재시도 키로 보호한 한 트랜잭션에서 checkin 저장 → 그 날짜부터 회복 재계산 → 2주 연속 낮음 안전 평가
 * → 응답 조합.
 * 컨디션 저장 뒤의 재측정도 같은 체크에 연결되고, 그날 이후 회복 상태가 다시 계산된다.
 * 저장 전에 사용자 행을 잠그고 접근 조건을 다시 확인해 건강 기록 삭제·동의 철회·탈퇴와 겹치지 않게 한다.
 * <p>
 * 컨디션까지 입력한 날의 재측정으로 회복이 낮음이 되면 오늘의 회복 추천도 만든다(이미 있으면 그대로 둔다).
 */
@Service
public class MeasurementSaveFlow {

    /** 건강 기록 삭제 때 {@code hrv-} 접두어로 재시도 응답 캐시도 지운다. */
    public static final String OPERATION = "hrv-measurement:create";

    private final MorningAccessGuard accessGuard;
    private final MeasurementCommands measurementCommands;
    private final RecoveryCommands recoveryCommands;
    private final LowRecoverySafetyCheck lowRecoverySafety;
    private final IdempotencyService idempotency;
    private final ServiceTime serviceTime;
    private final RecoverySuggestionFlow recoverySuggestions;

    public MeasurementSaveFlow(MorningAccessGuard accessGuard, MeasurementCommands measurementCommands,
            RecoveryCommands recoveryCommands, LowRecoverySafetyCheck lowRecoverySafety,
            IdempotencyService idempotency, ServiceTime serviceTime, RecoverySuggestionFlow recoverySuggestions) {
        this.accessGuard = accessGuard;
        this.measurementCommands = measurementCommands;
        this.recoveryCommands = recoveryCommands;
        this.lowRecoverySafety = lowRecoverySafety;
        this.idempotency = idempotency;
        this.serviceTime = serviceTime;
        this.recoverySuggestions = recoverySuggestions;
    }

    /**
     * @param request 재시도 키 비교용 원 요청(해시만 저장)
     */
    public IdempotentResponse<MeasurementSaveResult> save(long userId, LocalDate onboardingDate, UUID idempotencyKey,
            Object request, ValidatedMeasurement measurement) {
        return idempotency.execute(userId, OPERATION, idempotencyKey, request, MeasurementSaveResult.class, () -> {
            accessGuard.recheckMeasurementAccessInTransaction(userId);
            RecordedMeasurement recorded = record(userId, onboardingDate, measurement);
            recoveryCommands.recalculate(userId, recorded.checkDate(), serviceTime.today());
            lowRecoverySafety.evaluate(userId);
            recoverySuggestions.refresh(userId);
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
