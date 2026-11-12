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

    public Integer getConditionScore() {
        return conditionScore;
    }

    public Boolean getAlcohol() {
        return alcohol;
    }

    public Boolean getSick() {
        return sick;
    }

    public Boolean getCaffeine() {
        return caffeine;
    }

    public Instant getBreathingCompletedAt() {
        return breathingCompletedAt;
    }

    /** 컨디션·교란 요인 입력까지 끝났는지. */
    public boolean isConditionCompleted() {
        return completedAt != null;
    }

    /** 음주·아픔·카페인 중 하나라도 있으면 그날은 '내 평소' 계산에서 제외한다. 입력 전이면 false. */
    public boolean isConfounded() {
        return Boolean.TRUE.equals(alcohol) || Boolean.TRUE.equals(sick) || Boolean.TRUE.equals(caffeine);
    }

    /**
     * 컨디션·교란 요인을 저장한다. 다시 저장하면 값을 바꾸고, 완료 시각은 처음 완료한 시각을 유지한다.
     */
    public void saveCondition(int score, boolean alcohol, boolean sick, boolean caffeine, Instant now) {
        if (score < 1 || score > 5) {
            throw new IllegalArgumentException("컨디션은 1~5여야 합니다: " + score);
        }
        this.conditionScore = score;
        this.alcohol = alcohol;
        this.sick = sick;
        this.caffeine = caffeine;
        if (this.completedAt == null) {
            this.completedAt = now;
        }
    }
}
