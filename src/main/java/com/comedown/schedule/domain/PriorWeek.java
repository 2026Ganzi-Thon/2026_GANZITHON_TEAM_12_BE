package com.comedown.schedule.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 직전 주 한 주의 부하. {@code hasData}가 "집계 대상 일정이 있었던 주"인지 "동기화된 주"인지는
 * 정책 Q8이 확정되기 전까지 호출하는 쪽이 정한다.
 */
public record PriorWeek(BigDecimal loadScore, boolean hasData) {

    public PriorWeek {
        Objects.requireNonNull(loadScore, "loadScore");
    }
}
