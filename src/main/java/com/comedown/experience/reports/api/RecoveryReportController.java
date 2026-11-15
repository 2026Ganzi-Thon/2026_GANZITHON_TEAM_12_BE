package com.comedown.experience.reports.api;

import com.comedown.account.contract.HealthDataAccess;
import com.comedown.experience.reports.application.RecoveryReport;
import com.comedown.experience.reports.application.RecoveryReportQuery;
import com.comedown.shared.error.FieldValidationException;
import com.comedown.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/reports/recovery?days=14} → 200. 회복 리포트(C1, HTTP 책임: experience.reports).
 * 온보딩 완료(403) → days 7~90(400). 기준선 확정 전·동의 없음은 200 + locked.
 */
@RestController
public class RecoveryReportController {

    static final int MIN_DAYS = 7;
    static final int MAX_DAYS = 90;

    private final RecoveryReportQuery query;

    public RecoveryReportController(RecoveryReportQuery query) {
        this.query = query;
    }

    @GetMapping("/api/reports/recovery")
    public RecoveryReport report(CurrentUser user, @RequestParam(defaultValue = "14") int days) {
        HealthDataAccess access = query.requireOnboarded(user.id());
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw FieldValidationException.of("days", "7~90이어야 해요.");
        }
        return query.report(access, days);
    }
}
