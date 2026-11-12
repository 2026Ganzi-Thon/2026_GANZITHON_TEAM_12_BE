package com.comedown.shared.time;

import java.time.Instant;
import java.util.Objects;

/**
 * 반열린 시각 범위 {@code [start, endExclusive)}. start &lt; endExclusive 를 보장한다.
 */
public record InstantRange(Instant start, Instant endExclusive) {

    public InstantRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(endExclusive, "endExclusive");
        if (!start.isBefore(endExclusive)) {
            throw new IllegalArgumentException("start must be before endExclusive");
        }
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(endExclusive);
    }

    public boolean overlaps(InstantRange other) {
        return start.isBefore(other.endExclusive) && other.start.isBefore(endExclusive);
    }
}
