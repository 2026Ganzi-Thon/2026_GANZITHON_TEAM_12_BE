package com.comedown.checkin.application.port;

import com.comedown.checkin.contract.RecordCameraMeasurement;
import com.comedown.checkin.domain.measurement.HrvAnalyzer;

/**
 * 측정 원신호를 임계값 조정용으로 남기는 통로. 운영에서는 아무것도 하지 않는다.
 * local 프로필에서 설정을 켰을 때만 Git 제외 경로(recordings/)에 파일로 남긴다(API 명세 6절).
 */
public interface SignalRecorder {

    void record(long measurementId, RecordCameraMeasurement command, HrvAnalyzer.Result result);
}
