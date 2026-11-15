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
import com.comedown.shared.time.ServiceTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 측정 저장 유스케이스. 측정 시작 시각의 서울 날짜 체크를 확보하고(없으면 생성) 측정 1건을 저장한다.
 * 재측정은 같은 체크에 연결한다.
 */
@Service
public class MeasurementCommandService implements MeasurementCommands {

    private final MorningCheckProvisioner provisioner;
    private final HrvMeasurementRepository measurements;
    private final ServiceTime serviceTime;
    private final SignalRecorder signalRecorder;

    MeasurementCommandService(MorningCheckProvisioner provisioner, HrvMeasurementRepository measurements,
            ServiceTime serviceTime, SignalRecorder signalRecorder) {
        this.provisioner = provisioner;
        this.measurements = measurements;
        this.serviceTime = serviceTime;
        this.signalRecorder = signalRecorder;
    }

    @Override
    @Transactional
    public RecordedMeasurement recordCamera(RecordCameraMeasurement command) {
        HrvAnalyzer.Result result = HrvAnalyzer.analyze(command.t(), command.r(), command.g());
        MorningCheck check = provisioner.ensure(command.userId(), command.onboardingDate(),
                serviceTime.toServiceDate(command.startedAt()));
        HrvMeasurement saved = measurements.save(HrvMeasurement.camera(
                command.userId(), check.getId(), command.startedAt(), command.torch(), command.timeSource(),
                result, serviceTime.now()));
        signalRecorder.record(saved.getId(), command, result);
        return toRecorded(saved, check);
    }

    @Override
    @Transactional
    public RecordedMeasurement recordManual(RecordManualMeasurement command) {
        MorningCheck check = provisioner.ensure(command.userId(), command.onboardingDate(),
                serviceTime.toServiceDate(command.measuredAt()));
        HrvMeasurement saved = measurements.save(HrvMeasurement.manual(
                command.userId(), check.getId(), command.measuredAt(), command.rmssdMs(), serviceTime.now()));
        return toRecorded(saved, check);
    }

    private static RecordedMeasurement toRecorded(HrvMeasurement m, MorningCheck check) {
        return new RecordedMeasurement(
                m.getId(), check.getId(), check.getCheckDate(), m.getQualityLevel().name(), m.getRetryReason(),
                m.getRmssdMs(), m.getMeanHrBpm());
    }
}
