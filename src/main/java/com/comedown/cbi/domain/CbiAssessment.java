package com.comedown.cbi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** CBI 응답 1회(cbi_assessments). 13개 답(cbi_answers)과 한 트랜잭션으로 저장한다. */
@Entity
@Table(name = "cbi_assessments")
public class CbiAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "round", nullable = false, updatable = false, length = 20)
    private CbiRound round;

    @Column(name = "questionnaire_version", nullable = false, updatable = false, length = 20)
    private String questionnaireVersion;

    @Column(name = "personal_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal personalScore;

    @Column(name = "work_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal workScore;

    @Column(name = "high_risk", nullable = false)
    private boolean highRisk;

    @Column(name = "taken_at", nullable = false, updatable = false)
    private Instant takenAt;

    protected CbiAssessment() {
    }

    public static CbiAssessment of(long userId, CbiRound round, CbiQuestionnaire.Score score, Instant takenAt) {
        CbiAssessment a = new CbiAssessment();
        a.userId = userId;
        a.round = round;
        a.questionnaireVersion = CbiQuestionnaire.VERSION;
        a.personalScore = score.personal();
        a.workScore = score.work();
        a.highRisk = score.highRisk();
        a.takenAt = takenAt;
        return a;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public CbiRound getRound() {
        return round;
    }

    public String getQuestionnaireVersion() {
        return questionnaireVersion;
    }

    public BigDecimal getPersonalScore() {
        return personalScore;
    }

    public BigDecimal getWorkScore() {
        return workScore;
    }

    public boolean isHighRisk() {
        return highRisk;
    }

    public Instant getTakenAt() {
        return takenAt;
    }
}
