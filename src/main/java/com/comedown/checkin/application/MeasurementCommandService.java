package com.comedown.checkin.application;

import com.comedown.checkin.application.port.SignalRecorder;
import com.comedown.checkin.contract.MeasurementCommands;
import com.comedown.checkin.contract.RecordCameraMeasurement;
import com.comedown.checkin.contract.RecordManualMeasurement;
import com.comedown.checkin.contract.RecordedMeasurement;
import com.comedown.checkin.domain.HrvMeasurement;
import com.comedown.checkin.domain.MorningCheck;
import com.comedown.checkin.domain.measurement.HrvAnalyzer;
import com.comedown.checkin.infrastructure.persistence.HrvMeasurementRepository;
import com.comedown.checkin.infrastructure.persistence.MorningCheckRepository;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 측정 저장 유스케이스. 측정 날짜의 아침 체크를 확보하고(없으면 생성) 측정 1건을 저장한다.
 */
@Service
public class MeasurementCommandService implements MeasurementCommands {

    private final MorningCheckRepository morningChecks;
    private final HrvMeasurementRepository measurements;
    private final ServiceTime serviceTime;
    private final SignalRecorder signalRecorder;

    public MeasurementCommandService(MorningCheckRepository morningChecks, HrvMeasurementRepository measurements,
            ServiceTime serviceTime, SignalRecorder signalRecorder) {
        this.morningChecks = morningChecks;
        this.measurements = measurements;
        this.serviceTime = serviceTime;
        this.signalRecorder = signalRecorder;
    }

    @Override
    @Transactional
    public RecordedMeasurement recordCamera(RecordCameraMeasurement command) {
        HrvAnalyzer.Result result = HrvAnalyzer.analyze(command.t(), command.r(), command.g());
        MorningCheck check = ensureMorningCheck(command.userId(), command.onboardingDate(), command.startedAt());
        HrvMeasurement saved = measurements.save(HrvMeasurement.camera(
                command.userId(), check.getId(), command.startedAt(), command.torch(), command.timeSource(),
                result, serviceTime.now()));
        signalRecorder.record(saved.getId(), command, result);
        return toRecorded(saved);
    }

    @Override
    @Transactional
    public RecordedMeasurement recordManual(RecordManualMeasurement command) {
        MorningCheck check = ensureMorningCheck(command.userId(), command.onboardingDate(), command.measuredAt());
        HrvMeasurement saved = measurements.save(HrvMeasurement.manual(
                command.userId(), check.getId(), command.measuredAt(), command.rmssdMs(), serviceTime.now()));
        return toRecorded(saved);
    }

    /** 측정 시작 시각의 서울 날짜 체크를 확보한다. 재측정은 같은 체크에 연결한다. */
    private MorningCheck ensureMorningCheck(long userId, LocalDate onboardingDate, Instant measuredAt) {
        LocalDate checkDate = serviceTime.toServiceDate(measuredAt);
        int dayIndex = dayIndex(onboardingDate, checkDate);
        morningChecks.insertIfAbsent(userId, checkDate, dayIndex,
                LocalDateTime.ofInstant(serviceTime.now(), ZoneOffset.UTC));
        return morningChecks.findForUpdate(userId, checkDate)
                .orElseThrow(() -> new IllegalStateException("아침 체크를 만들지 못했습니다"));
    }

    /**
     * n일차 = 온보딩 완료 서울 날짜를 1일차로 센 값. 완료일 이전 날짜(자정 직전 측정 등)는 1일차로 둔다.
     */
    static int dayIndex(LocalDate onboardingDate, LocalDate checkDate) {
        long days = ChronoUnit.DAYS.between(onboardingDate, checkDate) + 1;
        return (int) Math.max(1, days);
    }

    private static RecordedMeasurement toRecorded(HrvMeasurement m) {
        return new RecordedMeasurement(
                m.getId(), m.getMorningCheckId(), m.getQualityLevel().name(), m.getRetryReason(),
                m.getRmssdMs(), m.getMeanHrBpm());
    }
}
