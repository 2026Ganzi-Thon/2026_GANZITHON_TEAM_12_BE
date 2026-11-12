package com.comedown.safety.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * 도움 받을 수 있는 곳 노출 기록(safety_alerts).
 * 닫기(acknowledged_at)는 조건 해제(resolved_at)와 다르다. 해제됐다가 다시 충족되면 새 행으로 노출한다.
 */
@Entity
@Table(name = "safety_alerts")
public class SafetyAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, updatable = false, length = 30)
    private SafetyTrigger triggerType;

    /** 근거(예: cbi_assessments.id, 낮음 시작 날짜). */
    @Column(name = "source_ref", length = 50, updatable = false)
    private String sourceRef;

    @Column(name = "triggered_at", nullable = false, updatable = false)
    private Instant triggeredAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected SafetyAlert() {
    }

    public static SafetyAlert trigger(long userId, SafetyTrigger type, String sourceRef, Instant now) {
        SafetyAlert alert = new SafetyAlert();
        alert.userId = userId;
        alert.triggerType = Objects.requireNonNull(type, "type");
        alert.sourceRef = sourceRef;
        alert.triggeredAt = Objects.requireNonNull(now, "now");
        return alert;
    }

    /** 사용자가 닫음. 조건 해제와 다르며 다시 불러도 처음 시각을 유지한다. */
    public void acknowledge(Instant now) {
        if (acknowledgedAt == null) {
            acknowledgedAt = now;
        }
    }

    /** 조건이 풀림(다시 충족되면 새 안내). */
    public void resolve(Instant now) {
        if (resolvedAt == null) {
            resolvedAt = now;
        }
    }

    public boolean isOpen() {
        return resolvedAt == null;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public SafetyTrigger getTriggerType() {
        return triggerType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
