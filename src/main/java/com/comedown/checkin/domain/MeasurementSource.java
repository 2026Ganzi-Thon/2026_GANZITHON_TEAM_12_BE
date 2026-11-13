package com.comedown.checkin.domain;

/** 측정 방식. */
public enum MeasurementSource {
    /** 휴대폰 카메라 밝기 신호를 서버가 계산. */
    CAMERA,
    /** 카메라를 못 쓰는 기기에서 워치 RMSSD 값을 직접 입력. */
    MANUAL
}
