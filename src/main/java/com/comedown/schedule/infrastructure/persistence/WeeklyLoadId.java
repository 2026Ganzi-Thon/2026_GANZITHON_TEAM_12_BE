package com.comedown.schedule.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

@Embeddable
public class WeeklyLoadId implements Serializable {

    @Column(name = "user_id")
    private long userId;

    @Column(name = "week_start")
    private LocalDate weekStart;

    protected WeeklyLoadId() {
    }

    public WeeklyLoadId(long userId, LocalDate weekStart) {
        this.userId = userId;
        this.weekStart = weekStart;
    }

    long userId() {
        return userId;
    }

    LocalDate weekStart() {
        return weekStart;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WeeklyLoadId other && userId == other.userId && Objects.equals(weekStart, other.weekStart);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, weekStart);
    }
}
