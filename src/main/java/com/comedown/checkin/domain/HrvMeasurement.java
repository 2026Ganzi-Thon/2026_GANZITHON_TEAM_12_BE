package com.comedown.checkin.domain;

import com.comedown.checkin.domain.measurement.HrvAnalyzer;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * HRV 측정 1건(hrv_measurements). 재측정도 각각 저장하고 RETRY도 행으로 남긴다.
 * CAMERA는 서버 계산값만 저장하며 원신호(t·r·g)는 저장하지 않는다.
 */
@Entity
@Table(name = "hrv_measurements")
public class HrvMeasurement {

    /** MANUAL 입력의 algorithm_version(계산하지 않음). */
    public static final String MANUAL_ALGORITHM_VERSION = "manual-v1";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private long userId;

    @Column(name = "morning_check_id", nullable = false, updatable = false)
    private long morningCheckId;

    @Column(name = "measured_at", nullable = false, updatable = false)
    private Instant measuredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 16)
    private MeasurementSource source;

    @Column(name = "rmssd_ms")
    private Double rmssdMs;

    @Column(name = "ln_rmssd")
    private Double lnRmssd;

    @Column(name = "mean_hr_bpm")
    private Double meanHrBpm;

    @Column(name = "valid_beats")
    private Integer validBeats;

    @Column(name = "rejected_beats")
    private Integer rejectedBeats;

    @Column(name = "noise_ratio")
    private Double noiseRatio;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_level", nullable = false, length = 12)
    private QualityLevel qualityLevel;

    @Column(name = "retry_reason", length = 20)
    private String retryReason;

    @Column(name = "algorithm_version", nullable = false, length = 30)
    private String algorithmVersion;

    @Column(name = "time_source", length = 16)
    private String timeSource;

    @Column(name = "frame_count")
    private Integer frameCount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "channel", length = 1)
    private String channel;

    @Column(name = "duration_sec")
    private Double durationSec;

    @Column(name = "fps")
    private Double fps;

    @Column(name = "torch")
    private Boolean torch;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected HrvMeasurement() {
    }

    /** 카메라 측정: 서버 계산 결과를 저장값으로 옮긴다. */
    public static HrvMeasurement camera(long userId, long morningCheckId, Instant startedAt, Boolean torch,
            String timeSource, HrvAnalyzer.Result result, Instant now) {
        Objects.requireNonNull(result, "result");
        HrvMeasurement m = base(userId, morningCheckId, startedAt, MeasurementSource.CAMERA, now);
        m.qualityLevel = QualityLevel.valueOf(result.quality());
        m.retryReason = result.retryReason();
        m.algorithmVersion = HrvAnalyzer.ALGORITHM_VERSION;
        m.torch = torch;
        m.timeSource = timeSource;
        m.channel = result.channel();
        m.frameCount = result.frameCount();
        m.durationSec = result.durationSec();
        m.fps = result.fps();
        m.noiseRatio = finiteOrNull(result.noiseRatio());
        if (result.totalIbiCount() > 0) {
            m.validBeats = result.validIbiCount();
            m.rejectedBeats = result.totalIbiCount() - result.validIbiCount();
        }
        if (result.ok()) {
            m.rmssdMs = result.rmssdMs();
            m.lnRmssd = result.lnRmssd();
            m.meanHrBpm = result.hrBpm();
        }
        return m;
    }

    /** 수동 입력: RMSSD(ms)만 받아 UNVERIFIED 참고값으로 저장한다. 기준선·자동 판정에는 쓰지 않는다. */
    public static HrvMeasurement manual(long userId, long morningCheckId, Instant measuredAt, double rmssdMs,
            Instant now) {
        if (!(rmssdMs > 0) || !Double.isFinite(rmssdMs)) {
            throw new IllegalArgumentException("rmssdMs는 0보다 큰 유한수여야 합니다");
        }
        HrvMeasurement m = base(userId, morningCheckId, measuredAt, MeasurementSource.MANUAL, now);
        m.qualityLevel = QualityLevel.UNVERIFIED;
        m.algorithmVersion = MANUAL_ALGORITHM_VERSION;
        m.rmssdMs = rmssdMs;
        m.lnRmssd = Math.log(rmssdMs);
        return m;
    }

    private static HrvMeasurement base(long userId, long morningCheckId, Instant measuredAt,
            MeasurementSource source, Instant now) {
        HrvMeasurement m = new HrvMeasurement();
        m.userId = userId;
        m.morningCheckId = morningCheckId;
        m.measuredAt = Objects.requireNonNull(measuredAt, "measuredAt");
        m.source = source;
        m.createdAt = Objects.requireNonNull(now, "now");
        return m;
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    public Long getId() {
        return id;
    }

    public long getMorningCheckId() {
        return morningCheckId;
    }

    public MeasurementSource getSource() {
        return source;
    }

    public QualityLevel getQualityLevel() {
        return qualityLevel;
    }

    public String getRetryReason() {
        return retryReason;
    }

    public Double getRmssdMs() {
        return rmssdMs;
    }

    public Double getMeanHrBpm() {
        return meanHrBpm;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public Double getLnRmssd() {
        return lnRmssd;
    }

    public String getChannel() {
        return channel;
    }
}
