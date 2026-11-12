package com.comedown.checkin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 하루 아침 체크(morning_checks). 사용자별 하루 1행이며 측정·컨디션·호흡 중 첫 저장 때 만든다.
 * 결과 계산과 완료(completed_at)는 컨디션 저장 기준이다.
 */
@Entity
@Table(name = "morning_checks")
public class MorningCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private long userId;

    @Column(name = "check_date", nullable = false, updatable = false)
    private LocalDate checkDate;

    /** 생성 당시 n일차 스냅샷. */
    @Column(name = "day_index", nullable = false, updatable = false)
    private int dayIndex;

    @Column(name = "breathing_completed_at")
    private Instant breathingCompletedAt;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "condition_score")
    private Integer conditionScore;

    @Column(name = "alcohol")
    private Boolean alcohol;

    @Column(name = "sick")
    private Boolean sick;

    @Column(name = "caffeine")
    private Boolean caffeine;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MorningCheck() {
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public LocalDate getCheckDate() {
        return checkDate;
    }

    public int getDayIndex() {
        return dayIndex;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    /** 컨디션·교란 요인 입력까지 끝났는지. */
    public boolean isConditionCompleted() {
        return completedAt != null;
    }
}
