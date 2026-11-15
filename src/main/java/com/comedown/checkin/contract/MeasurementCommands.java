package com.comedown.checkin.contract;

/**
 * checkin이 다른 기능(experience.morning)에 공개하는 측정 저장 계약.
 * 측정 날짜는 측정 시작 시각의 서울 날짜이며, 그날 아침 체크가 없으면 만들어 연결한다.
 * 접근 조건(온보딩·동의·측정 켜짐)과 요청 형식·시각 검증은 호출하는 쪽이 먼저 끝낸다.
 */
public interface MeasurementCommands {

    /** 카메라 밝기 신호로 RMSSD·품질을 계산해 저장한다(hrv-cam-v1). 원신호는 저장하지 않는다. */
    RecordedMeasurement recordCamera(RecordCameraMeasurement command);

    /** 워치 RMSSD 값을 UNVERIFIED 참고값으로 저장한다(manual-v1). */
    RecordedMeasurement recordManual(RecordManualMeasurement command);
}
