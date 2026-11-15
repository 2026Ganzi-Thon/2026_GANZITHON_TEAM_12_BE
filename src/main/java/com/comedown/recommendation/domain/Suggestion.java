package com.comedown.recommendation.domain;

import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 과부하 대비 제안·회복 일정 추천(suggestions). 상태 전이 규칙(API 명세 5절)을 이 엔티티가 지킨다.
 * <ul>
 *   <li>dismiss: PROPOSED에서만. 반복은 같은 결과</li>
 *   <li>done: APPROVED이고 일정이 끝난 뒤에만. doneAt만 채우고 상태 유지. 반복은 같은 결과</li>
 *   <li>undo: APPROVED이고 했어요 전일 때만. 반복은 같은 결과</li>
 *   <li>승인: PROPOSED·FAILED → APPROVING → APPROVED, 확정 실패만 APPROVING → FAILED(타임아웃은 FAILED로 확정하지 않음)</li>
 *   <li>그 외 전이는 409 INVALID_STATE(승인 시작은 SUGGESTION_NOT_PROPOSED)</li>
 * </ul>
 * registration_key는 생성 때 서버가 만든 Google event id이며 승인 재시도에 그대로 쓴다.
 */
@Entity
@Table(name = "suggestions")
public class Suggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 20)
    private SuggestionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SuggestionStatus status;

    @Column(name = "target_date", nullable = false, updatable = false)
    private LocalDate targetDate;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "activity_code", length = 30)
    private String activityCode;

    @Column(name = "judgment_state", length = 30)
    private String judgmentState;

    @Column(name = "reason_code", nullable = false, length = 30)
    private String reasonCode;

    @Column(name = "calculation_version", nullable = false, length = 30)
    private String calculationVersion;

    @Column(name = "message", length = 300)
    private String message;

    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated;

    @Column(name = "is_fallback", nullable = false)
    private boolean fallback;

    @Column(name = "registration_key", nullable = false, updatable = false, length = 64)
    private String registrationKey;

    @Column(name = "related_event_id")
    private Long relatedEventId;

    @Column(name = "risk_week_start")
    private LocalDate riskWeekStart;

    @Column(name = "google_event_id", length = 255)
    private String googleEventId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Suggestion() {
    }

    /** 새 제안(PROPOSED). */
    public static Suggestion propose(long userId, SuggestionType type, LocalDate targetDate, Instant startAt,
            Instant endAt, String title, String activityCode, String judgmentState, String reasonCode,
            String calculationVersion, String message, boolean aiGenerated, boolean fallback, Long relatedEventId,
            LocalDate riskWeekStart, Instant expiresAt, String registrationKey, Instant now) {
        requirePeriod(startAt, endAt);
        Suggestion s = new Suggestion();
        s.userId = userId;
        s.type = Objects.requireNonNull(type, "type");
        s.status = SuggestionStatus.PROPOSED;
        s.targetDate = Objects.requireNonNull(targetDate, "targetDate");
        s.startAt = startAt;
        s.endAt = endAt;
        s.title = Objects.requireNonNull(title, "title");
        s.activityCode = activityCode;
        s.judgmentState = judgmentState;
        s.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        s.calculationVersion = Objects.requireNonNull(calculationVersion, "calculationVersion");
        s.message = message;
        s.aiGenerated = aiGenerated;
        s.fallback = fallback;
        s.relatedEventId = relatedEventId;
        s.riskWeekStart = riskWeekStart;
        s.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        s.registrationKey = Objects.requireNonNull(registrationKey, "registrationKey");
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }

    // ---------- 사용자 행동 ----------

    /** 건너뛰기/나중에. PROPOSED에서만. */
    public void dismiss(Instant now) {
        if (status == SuggestionStatus.DISMISSED) {
            return;
        }
        if (status != SuggestionStatus.PROPOSED) {
            throw invalidState();
        }
        status = SuggestionStatus.DISMISSED;
        decidedAt = now;
        updatedAt = now;
    }

    /** "했어요". APPROVED이고 일정이 끝난 뒤에만 doneAt을 채운다(상태 유지). */
    public void markDone(Instant now) {
        if (status != SuggestionStatus.APPROVED) {
            throw invalidState();
        }
        if (doneAt != null) {
            return;
        }
        if (now.isBefore(endAt)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "일정이 끝난 뒤에 체크할 수 있어요.");
        }
        doneAt = now;
        updatedAt = now;
    }

    // ---------- 승인 흐름(experience.suggestions가 조합) ----------

    /**
     * 승인 시작. PROPOSED·FAILED → APPROVING. 시간을 바꾸면 같은 targetDate·같은 길이여야 한다(그 외 조건은 호출하는 쪽).
     *
     * @return true면 이번에 APPROVING으로 바꿈, false면 이미 APPROVING·APPROVED(같은 승인 재시도)
     */
    public boolean beginApproval(Instant newStart, Instant newEnd, Instant now) {
        if (status == SuggestionStatus.APPROVING || status == SuggestionStatus.APPROVED) {
            return false;
        }
        if (status != SuggestionStatus.PROPOSED && status != SuggestionStatus.FAILED) {
            throw new ApiException(ErrorCode.SUGGESTION_NOT_PROPOSED);
        }
        if (newStart != null || newEnd != null) {
            if (newStart == null || newEnd == null) {
                throw new IllegalArgumentException("시작·끝 시각을 함께 보내야 합니다");
            }
            requirePeriod(newStart, newEnd);
            if (!Duration.between(newStart, newEnd).equals(duration())) {
                throw new IllegalArgumentException("제안과 같은 길이여야 합니다");
            }
            startAt = newStart;
            endAt = newEnd;
        }
        status = SuggestionStatus.APPROVING;
        updatedAt = now;
        return true;
    }

    /** Google 등록 확인 후 APPROVING → APPROVED. 같은 event id로 반복하면 같은 결과. */
    public void confirmApproved(String googleEventId, Instant now) {
        Objects.requireNonNull(googleEventId, "googleEventId");
        if (status == SuggestionStatus.APPROVED && googleEventId.equals(this.googleEventId)) {
            return;
        }
        if (status != SuggestionStatus.APPROVING) {
            throw invalidState();
        }
        this.googleEventId = googleEventId;
        status = SuggestionStatus.APPROVED;
        decidedAt = now;
        updatedAt = now;
    }

    /** 확정 등록 실패만 APPROVING → FAILED. 타임아웃은 등록 키로 조회해 확인하고 이걸 부르지 않는다. */
    public void markFailed(Instant now) {
        if (status == SuggestionStatus.FAILED) {
            return;
        }
        if (status != SuggestionStatus.APPROVING) {
            throw invalidState();
        }
        status = SuggestionStatus.FAILED;
        updatedAt = now;
    }

    /** 되돌리기 가능 여부. APPROVED이고 했어요 전. UNDONE은 반복 호출(같은 결과)로 허용. */
    public void requireUndoable() {
        if (status == SuggestionStatus.UNDONE) {
            return;
        }
        if (status != SuggestionStatus.APPROVED || doneAt != null) {
            throw invalidState();
        }
    }

    /** Google 일정 삭제(이미 없는 404 포함) 확인 후 APPROVED → UNDONE. */
    public void confirmUndone(Instant now) {
        requireUndoable();
        if (status == SuggestionStatus.UNDONE) {
            return;
        }
        status = SuggestionStatus.UNDONE;
        updatedAt = now;
    }

    /** 만료: PROPOSED에서만. */
    public boolean expireIfDue(Instant now) {
        if (status != SuggestionStatus.PROPOSED || now.isBefore(expiresAt)) {
            return false;
        }
        status = SuggestionStatus.EXPIRED;
        updatedAt = now;
        return true;
    }

    public Duration duration() {
        return Duration.between(startAt, endAt);
    }

    private static void requirePeriod(Instant start, Instant end) {
        Objects.requireNonNull(start, "startAt");
        Objects.requireNonNull(end, "endAt");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("끝 시각은 시작 시각보다 늦어야 합니다");
        }
    }

    private static ApiException invalidState() {
        return new ApiException(ErrorCode.INVALID_STATE);
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public SuggestionType getType() {
        return type;
    }

    public SuggestionStatus getStatus() {
        return status;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public String getTitle() {
        return title;
    }

    public String getActivityCode() {
        return activityCode;
    }

    public String getJudgmentState() {
        return judgmentState;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getCalculationVersion() {
        return calculationVersion;
    }

    public String getMessage() {
        return message;
    }

    public boolean isAiGenerated() {
        return aiGenerated;
    }

    public boolean isFallback() {
        return fallback;
    }

    public String getRegistrationKey() {
        return registrationKey;
    }

    public Long getRelatedEventId() {
        return relatedEventId;
    }

    public LocalDate getRiskWeekStart() {
        return riskWeekStart;
    }

    public String getGoogleEventId() {
        return googleEventId;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getDoneAt() {
        return doneAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
