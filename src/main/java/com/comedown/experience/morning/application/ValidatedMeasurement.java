package com.comedown.experience.morning.application;

import java.time.Instant;

/** 형식·시각 검증을 통과한 측정 입력. */
public sealed interface ValidatedMeasurement {

    /** 측정 날짜 판정 기준 시각. */
    Instant startedAt();

    record Camera(Instant startedAt, Boolean torch, String timeSource, double[] t, double[] r, double[] g)
            implements ValidatedMeasurement {
    }

    record Manual(Instant startedAt, double rmssdMs) implements ValidatedMeasurement {
    }
}
