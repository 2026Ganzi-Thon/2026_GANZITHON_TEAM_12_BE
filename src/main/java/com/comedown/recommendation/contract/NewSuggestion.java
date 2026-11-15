package com.comedown.recommendation.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 새 제안 생성 명령(PRE_START 규칙·RECOVERY 규칙이 만든다). 빈 시간·문구·근거 판단은 만드는 쪽의 정책이다.
 *
 * @param type                PRE_START·RECOVERY
 * @param reasonCode          RISK_WEEK·LOW_RECOVERY·LOW_CONDITION·OVERLOAD …
 * @param calculationVersion  근거가 된 계산 정책 버전(예: load-v1, recovery-v1)
 * @param expiresAt           지나면 EXPIRED
 */
public record NewSuggestion(
        long userId,
        String type,
        LocalDate targetDate,
        Instant start,
        Instant end,
        String title,
        String activityCode,
        String judgmentState,
        String reasonCode,
        String calculationVersion,
        String message,
        boolean aiGenerated,
        boolean fallback,
        Long relatedEventId,
        LocalDate riskWeekStart,
        Instant expiresAt) {

    public NewSuggestion {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetDate, "targetDate");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(calculationVersion, "calculationVersion");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
