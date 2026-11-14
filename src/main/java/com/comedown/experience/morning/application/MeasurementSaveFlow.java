package com.comedown.experience.morning.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.checkin.contract.MeasurementCommands;
import com.comedown.checkin.contract.RecordCameraMeasurement;
import com.comedown.checkin.contract.RecordManualMeasurement;
import com.comedown.checkin.contract.RecordedMeasurement;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.idempotency.IdempotencyService;
import com.comedown.shared.idempotency.IdempotentResponse;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 측정 저장 흐름: 접근 조건 확인 → 재시도 키로 보호한 checkin 저장 → 응답 조합.
 * <p>
 * 아직 하지 않는 일: 컨디션 저장 뒤 재측정했을 때 회복 상태·리포트·미승인 추천 재계산.
 * recovery 저장(hrv_daily_states)과 추천이 구현되면 이 흐름에 연결한다.
 */
@Service
public class MeasurementSaveFlow {

    /** 건강 기록 삭제 때 {@code hrv-} 접두어로 재시도 응답 캐시도 지운다. */
    public static final String OPERATION = "hrv-measurement:create";
    static final String DELETING_MESSAGE = "탈퇴를 처리하고 있어요.";

    private final AccountAccessQuery accountAccess;
    private final MeasurementCommands measurementCommands;
    private final IdempotencyService idempotency;
    private final ServiceTime serviceTime;

    public MeasurementSaveFlow(AccountAccessQuery accountAccess, MeasurementCommands measurementCommands,
            IdempotencyService idempotency, ServiceTime serviceTime) {
        this.accountAccess = accountAccess;
        this.measurementCommands = measurementCommands;
        this.idempotency = idempotency;
        this.serviceTime = serviceTime;
    }

    /**
     * 측정 저장 접근 조건(API 명세 0절): 온보딩 완료 → 건강정보 동의 → 측정 켜짐.
     *
     * @return 온보딩 완료 서울 날짜(n일차 기준)
     */
    public LocalDate requireMeasurementAccess(long userId) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!access.active()) {
            throw new ApiException(ErrorCode.INVALID_STATE, DELETING_MESSAGE);
        }
        if (!access.onboardingCompleted()) {
            throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
        }
        if (!access.sensitiveHrvConsented()) {
            throw new ApiException(ErrorCode.HRV_CONSENT_REQUIRED);
        }
        if (!access.hrvEnabled()) {
            throw new ApiException(ErrorCode.HRV_DISABLED);
        }
        return serviceTime.toServiceDate(access.onboardingCompletedAt());
    }

    /**
     * @param request 재시도 키 비교용 원 요청(해시만 저장)
     */
    public IdempotentResponse<MeasurementSaveResult> save(long userId, LocalDate onboardingDate, UUID idempotencyKey,
            Object request, ValidatedMeasurement measurement) {
        return idempotency.execute(userId, OPERATION, idempotencyKey, request, MeasurementSaveResult.class,
                () -> IdempotentResponse.of(201, toResult(record(userId, onboardingDate, measurement))));
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
