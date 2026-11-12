package com.comedown.checkin.infrastructure.recording;

import com.comedown.checkin.application.port.SignalRecorder;
import com.comedown.checkin.contract.RecordCameraMeasurement;
import com.comedown.checkin.domain.measurement.HrvAnalyzer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** local이 아닌 모든 프로필: 원신호를 남기지 않는다(설정으로 켤 수 없음). */
@Component
@Profile("!local")
class NoopSignalRecorder implements SignalRecorder {

    @Override
    public void record(long measurementId, RecordCameraMeasurement command, HrvAnalyzer.Result result) {
        // 운영에서는 원신호를 어디에도 남기지 않는다.
    }
}
