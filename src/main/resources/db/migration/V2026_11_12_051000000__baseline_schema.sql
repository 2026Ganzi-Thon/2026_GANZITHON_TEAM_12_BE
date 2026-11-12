-- Come-down 기준선 스키마 (ERD v0.3, 2026-11-12: v0.2 + 백엔드 설계 수정 요청 1 반영)
-- 원칙: MySQL 8.4 / InnoDB / utf8mb4. 순간은 DATETIME(6) UTC, 하루 단위 값은 서울 날짜 DATE.
-- enum은 VARCHAR로 저장하고 허용 값은 애플리케이션에서 관리한다.
-- users로 향하는 FK는 ON DELETE CASCADE. 그 외 FK 규칙은 각 테이블에 명시한다.
-- 이 파일은 공유 후 수정하지 않는다. 변경은 새 migration으로 추가한다.

-- =====================================================================
-- 2-1. 회원·설정 (account, subscription)
-- =====================================================================

CREATE TABLE users (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    google_sub              VARCHAR(64)  NOT NULL,
    email                   VARCHAR(320) NULL,
    nickname                VARCHAR(20)  NULL,
    onboarding_step         VARCHAR(20)  NOT NULL,
    onboarding_completed_at DATETIME(6)  NULL,
    created_at              DATETIME(6)  NOT NULL,
    last_login_at           DATETIME(6)  NOT NULL,
    deletion_status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    deletion_requested_at   DATETIME(6)  NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_google_sub UNIQUE (google_sub)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_consents (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    consent_type VARCHAR(30) NOT NULL,
    version      VARCHAR(20) NOT NULL,
    agreed_at    DATETIME(6) NOT NULL,
    withdrawn_at DATETIME(6) NULL,
    CONSTRAINT pk_user_consents PRIMARY KEY (id),
    CONSTRAINT fk_user_consents_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX ix_user_consents_user_type_agreed (user_id, consent_type, agreed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_settings (
    user_id                  BIGINT       NOT NULL,
    measure_time             TIME         NULL,
    sleep_start              TIME         NOT NULL DEFAULT '23:00:00',
    sleep_end                TIME         NOT NULL DEFAULT '07:00:00',
    hrv_enabled              BOOLEAN      NOT NULL DEFAULT FALSE,
    reminder_google_event_id VARCHAR(255) NULL,
    updated_at               DATETIME(6)  NOT NULL,
    CONSTRAINT pk_user_settings PRIMARY KEY (user_id),
    CONSTRAINT fk_user_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_preferred_activities (
    user_id       BIGINT      NOT NULL,
    activity_code VARCHAR(30) NOT NULL,
    CONSTRAINT pk_user_preferred_activities PRIMARY KEY (user_id, activity_code),
    CONSTRAINT fk_user_preferred_activities_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE oauth_tokens (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    user_id                 BIGINT        NOT NULL,
    provider                VARCHAR(20)   NOT NULL,
    principal_name          VARCHAR(64)   NOT NULL,
    access_token_enc        TEXT          NOT NULL,
    refresh_token_enc       TEXT          NULL,
    access_token_issued_at  DATETIME(6)   NULL,
    access_token_expires_at DATETIME(6)   NULL,
    scopes                  VARCHAR(1000) NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,
    CONSTRAINT pk_oauth_tokens PRIMARY KEY (id),
    CONSTRAINT uk_oauth_tokens_provider_principal UNIQUE (provider, principal_name),
    CONSTRAINT uk_oauth_tokens_user_provider UNIQUE (user_id, provider),
    CONSTRAINT fk_oauth_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE subscriptions (
    user_id            BIGINT      NOT NULL,
    plan               VARCHAR(20) NOT NULL,
    trial_started_at   DATETIME(6) NOT NULL,
    trial_ends_at      DATETIME(6) NOT NULL,
    current_period_end DATETIME(6) NULL,
    updated_at         DATETIME(6) NOT NULL,
    CONSTRAINT pk_subscriptions PRIMARY KEY (user_id),
    CONSTRAINT fk_subscriptions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_subscriptions_trial_period CHECK (trial_ends_at > trial_started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 2-2. 번아웃 체크 (cbi)
-- =====================================================================

CREATE TABLE cbi_assessments (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    user_id               BIGINT       NOT NULL,
    round                 VARCHAR(20)  NOT NULL,
    questionnaire_version VARCHAR(20)  NOT NULL,
    personal_score        DECIMAL(5,2) NOT NULL,
    work_score            DECIMAL(5,2) NOT NULL,
    high_risk             BOOLEAN      NOT NULL,
    taken_at              DATETIME(6)  NOT NULL,
    CONSTRAINT pk_cbi_assessments PRIMARY KEY (id),
    -- MVP는 INITIAL·DAY28만 지원. FOLLOW_UP은 회차 번호 설계 후 별도 migration으로 활성화.
    CONSTRAINT uk_cbi_assessments_user_round UNIQUE (user_id, round),
    CONSTRAINT fk_cbi_assessments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_cbi_assessments_personal_score CHECK (personal_score BETWEEN 0 AND 100),
    CONSTRAINT ck_cbi_assessments_work_score CHECK (work_score BETWEEN 0 AND 100),
    INDEX ix_cbi_assessments_user_taken (user_id, taken_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE cbi_answers (
    assessment_id BIGINT  NOT NULL,
    item_no       TINYINT NOT NULL,
    answer_option TINYINT NOT NULL,
    answer_value  TINYINT NOT NULL,
    CONSTRAINT pk_cbi_answers PRIMARY KEY (assessment_id, item_no),
    CONSTRAINT fk_cbi_answers_assessment FOREIGN KEY (assessment_id) REFERENCES cbi_assessments (id) ON DELETE CASCADE,
    CONSTRAINT ck_cbi_answers_item_no CHECK (item_no BETWEEN 1 AND 13),
    CONSTRAINT ck_cbi_answers_option CHECK (answer_option BETWEEN 0 AND 4),
    CONSTRAINT ck_cbi_answers_value CHECK (answer_value IN (0, 25, 50, 75, 100))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 2-3. 캘린더·일정 부하 (calendar, schedule)
-- =====================================================================

CREATE TABLE calendar_events (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    user_id            BIGINT       NOT NULL,
    calendar_id        VARCHAR(255) NOT NULL DEFAULT 'primary',
    google_event_id    VARCHAR(255) NOT NULL,
    recurring_event_id VARCHAR(255) NULL,
    title_hash         CHAR(64)     NULL,
    start_at           DATETIME(6)  NOT NULL,
    end_at             DATETIME(6)  NOT NULL,
    all_day            BOOLEAN      NOT NULL,
    event_status       VARCHAR(20)  NOT NULL,
    transparency       VARCHAR(20)  NOT NULL,
    created_by_app     BOOLEAN      NOT NULL,
    category           VARCHAR(30)  NULL,
    category_source    VARCHAR(10)  NULL,
    burden             TINYINT      NULL,
    adjustable         BOOLEAN      NULL,
    is_deadline        BOOLEAN      NULL,
    classified_at      DATETIME(6)  NULL,
    google_updated_at  DATETIME(6)  NULL,
    synced_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_calendar_events PRIMARY KEY (id),
    CONSTRAINT uk_calendar_events_google UNIQUE (user_id, calendar_id, google_event_id),
    -- suggestions(user_id, related_event_id) 복합 FK 대상: 같은 사용자 일정만 참조하게 한다.
    CONSTRAINT uk_calendar_events_user_id UNIQUE (user_id, id),
    CONSTRAINT fk_calendar_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_calendar_events_period CHECK (end_at > start_at),
    CONSTRAINT ck_calendar_events_burden CHECK (burden IS NULL OR burden BETWEEN 1 AND 3),
    INDEX ix_calendar_events_user_start (user_id, start_at),
    INDEX ix_calendar_events_user_recurring (user_id, recurring_event_id),
    INDEX ix_calendar_events_user_title_hash (user_id, title_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE event_category_overrides (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    calendar_id VARCHAR(255) NOT NULL DEFAULT 'primary',
    scope       VARCHAR(10)  NOT NULL,
    match_key   VARCHAR(255) NOT NULL,
    category    VARCHAR(30)  NOT NULL,
    burden      TINYINT      NULL,
    adjustable  BOOLEAN      NULL,
    is_deadline BOOLEAN      NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_event_category_overrides PRIMARY KEY (id),
    CONSTRAINT uk_event_category_overrides_rule UNIQUE (user_id, calendar_id, scope, match_key),
    CONSTRAINT fk_event_category_overrides_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_event_category_overrides_burden CHECK (burden IS NULL OR burden BETWEEN 1 AND 3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE weekly_loads (
    user_id             BIGINT       NOT NULL,
    week_start          DATE         NOT NULL,
    total_hours         DECIMAL(5,1) NOT NULL,
    load_score          DECIMAL(6,1) NOT NULL,
    vs_usual_pct        DECIMAL(6,1) NULL,
    -- vs_usual_pct가 null인 이유: ZERO_BASELINE / INSUFFICIENT_DATA (API unavailableReason)
    vs_usual_unavailable_reason VARCHAR(30) NULL,
    deadline_count      SMALLINT     NOT NULL,
    late_night_count    SMALLINT     NOT NULL,
    weekend_hours       DECIMAL(5,1) NOT NULL,
    rest_days           TINYINT      NOT NULL,
    risk_level          VARCHAR(10)  NOT NULL,
    risk_reasons        JSON         NOT NULL DEFAULT (JSON_ARRAY()),
    calculation_version VARCHAR(30)  NOT NULL,
    computed_at         DATETIME(6)  NOT NULL,
    CONSTRAINT pk_weekly_loads PRIMARY KEY (user_id, week_start),
    CONSTRAINT fk_weekly_loads_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_weekly_loads_rest_days CHECK (rest_days BETWEEN 0 AND 7),
    CONSTRAINT ck_weekly_loads_risk_reasons CHECK (JSON_TYPE(risk_reasons) = 'ARRAY')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 2-4. 아침 1분 체크·회복 (checkin, recovery)
-- =====================================================================

CREATE TABLE morning_checks (
    id                     BIGINT      NOT NULL AUTO_INCREMENT,
    user_id                BIGINT      NOT NULL,
    check_date             DATE        NOT NULL,
    day_index              INT         NOT NULL,
    breathing_completed_at DATETIME(6) NULL,
    condition_score        TINYINT     NULL,
    alcohol                BOOLEAN     NULL,
    sick                   BOOLEAN     NULL,
    caffeine               BOOLEAN     NULL,
    completed_at           DATETIME(6) NULL,
    created_at             DATETIME(6) NOT NULL,
    CONSTRAINT pk_morning_checks PRIMARY KEY (id),
    CONSTRAINT uk_morning_checks_user_date UNIQUE (user_id, check_date),
    -- hrv_measurements(user_id, morning_check_id) 복합 FK 대상
    CONSTRAINT uk_morning_checks_user_id UNIQUE (user_id, id),
    CONSTRAINT fk_morning_checks_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_morning_checks_condition CHECK (condition_score IS NULL OR condition_score BETWEEN 1 AND 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- CAMERA는 서버가 밝기 배열로 RMSSD·품질을 계산해 저장한다(원신호 t·r·g는 저장하지 않음).
-- RETRY(재측정 필요)도 행으로 남기며 이때 rmssd_ms·ln_rmssd는 null, retry_reason은 필수.
CREATE TABLE hrv_measurements (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    user_id           BIGINT      NOT NULL,
    morning_check_id  BIGINT      NOT NULL,
    measured_at       DATETIME(6) NOT NULL,
    source            VARCHAR(16) NOT NULL,
    rmssd_ms          DOUBLE      NULL,
    ln_rmssd          DOUBLE      NULL,
    mean_hr_bpm       DOUBLE      NULL,
    valid_beats       INT         NULL,
    rejected_beats    INT         NULL,
    noise_ratio       DOUBLE      NULL,
    quality_level     VARCHAR(12) NOT NULL,
    retry_reason      VARCHAR(20) NULL,
    algorithm_version VARCHAR(30) NOT NULL,
    time_source       VARCHAR(16) NULL,
    frame_count       INT         NULL,
    channel           CHAR(1)     NULL,
    duration_sec      DOUBLE      NULL,
    fps               DOUBLE      NULL,
    torch             BOOLEAN     NULL,
    created_at        DATETIME(6) NOT NULL,
    CONSTRAINT pk_hrv_measurements PRIMARY KEY (id),
    CONSTRAINT fk_hrv_measurements_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- 같은 사용자의 아침 체크만 참조
    CONSTRAINT fk_hrv_measurements_morning_check FOREIGN KEY (user_id, morning_check_id)
        REFERENCES morning_checks (user_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_hrv_measurements_rmssd CHECK (rmssd_ms IS NULL OR rmssd_ms > 0),
    -- RETRY가 아니면 RMSSD가 있어야 하고, RETRY면 사유가 있어야 한다.
    CONSTRAINT ck_hrv_measurements_value_present CHECK (quality_level = 'RETRY' OR rmssd_ms IS NOT NULL),
    CONSTRAINT ck_hrv_measurements_retry_reason CHECK ((quality_level = 'RETRY') = (retry_reason IS NOT NULL)),
    INDEX ix_hrv_measurements_user_measured (user_id, measured_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE hrv_daily_states (
    user_id               BIGINT       NOT NULL,
    state_date            DATE         NOT NULL,
    daily_ln_rmssd        DOUBLE       NULL,
    daily_rmssd_ms        DOUBLE       NULL,
    measurement_count     INT          NOT NULL DEFAULT 0,
    quality_level         VARCHAR(12)  NULL,
    confounded            BOOLEAN      NOT NULL,
    baseline_established  BOOLEAN      NOT NULL,
    baseline_valid_days   SMALLINT     NOT NULL,
    baseline_mean_ln      DOUBLE       NULL,
    baseline_sd_ln        DOUBLE       NULL,
    baseline_sample_count SMALLINT     NOT NULL,
    baseline_frozen       BOOLEAN      NOT NULL,
    trend_ln              DOUBLE       NULL,
    change_pct            DECIMAL(6,1) NULL,
    recovery_status       VARCHAR(20)  NOT NULL,
    calculation_version   VARCHAR(30)  NOT NULL,
    computed_at           DATETIME(6)  NOT NULL,
    CONSTRAINT pk_hrv_daily_states PRIMARY KEY (user_id, state_date),
    CONSTRAINT fk_hrv_daily_states_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_hrv_daily_states_measurement_count CHECK (measurement_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 2-5. 제안·추천, AI, 안전 (recommendation, ai, safety)
-- =====================================================================

CREATE TABLE suggestions (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    type                VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    target_date         DATE         NOT NULL,
    start_at            DATETIME(6)  NOT NULL,
    end_at              DATETIME(6)  NOT NULL,
    title               VARCHAR(100) NOT NULL,
    activity_code       VARCHAR(30)  NULL,
    judgment_state      VARCHAR(30)  NULL,
    reason_code         VARCHAR(30)  NOT NULL,
    calculation_version VARCHAR(30)  NOT NULL,
    message             VARCHAR(300) NULL,
    ai_generated        BOOLEAN      NOT NULL DEFAULT FALSE,
    is_fallback         BOOLEAN      NOT NULL DEFAULT FALSE,
    registration_key    VARCHAR(64)  NOT NULL,
    related_event_id    BIGINT       NULL,
    risk_week_start     DATE         NULL,
    google_event_id     VARCHAR(255) NULL,
    decided_at          DATETIME(6)  NULL,
    done_at             DATETIME(6)  NULL,
    expires_at          DATETIME(6)  NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_suggestions PRIMARY KEY (id),
    CONSTRAINT uk_suggestions_registration_key UNIQUE (registration_key),
    CONSTRAINT fk_suggestions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- 같은 사용자 일정만 참조. 원본 일정 삭제 전에 앱에서 related_event_id를 null로 바꾼다(ERD 6절).
    CONSTRAINT fk_suggestions_related_event FOREIGN KEY (user_id, related_event_id)
        REFERENCES calendar_events (user_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_suggestions_period CHECK (end_at > start_at),
    INDEX ix_suggestions_user_status_start (user_id, status, start_at),
    INDEX ix_suggestions_user_target_date (user_id, target_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ai_outputs (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    kind        VARCHAR(30) NOT NULL,
    ref_date    DATE        NOT NULL,
    input_hash  CHAR(64)    NOT NULL,
    content     TEXT        NOT NULL,
    model       VARCHAR(50) NULL,
    is_fallback BOOLEAN     NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_ai_outputs PRIMARY KEY (id),
    CONSTRAINT uk_ai_outputs_cache UNIQUE (user_id, kind, ref_date, input_hash),
    CONSTRAINT fk_ai_outputs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE safety_alerts (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    trigger_type    VARCHAR(30) NOT NULL,
    source_ref      VARCHAR(50) NULL,
    triggered_at    DATETIME(6) NOT NULL,
    acknowledged_at DATETIME(6) NULL,
    resolved_at     DATETIME(6) NULL,
    CONSTRAINT pk_safety_alerts PRIMARY KEY (id),
    CONSTRAINT fk_safety_alerts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX ix_safety_alerts_user_triggered (user_id, triggered_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- 6. 무결성·재시도 보완 (shared.idempotency)
-- =====================================================================

CREATE TABLE api_idempotency_keys (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    operation       VARCHAR(80) NOT NULL,
    request_key     VARCHAR(36) NOT NULL,
    request_hash    CHAR(64)    NOT NULL,
    status          VARCHAR(20) NOT NULL,
    response_status SMALLINT    NULL,
    response_body   JSON        NULL,
    created_at      DATETIME(6) NOT NULL,
    expires_at      DATETIME(6) NOT NULL,
    CONSTRAINT pk_api_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT uk_api_idempotency_keys_request UNIQUE (user_id, operation, request_key),
    CONSTRAINT fk_api_idempotency_keys_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX ix_api_idempotency_keys_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
