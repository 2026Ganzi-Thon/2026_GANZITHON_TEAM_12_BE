package com.comedown.experience.reports.api;

import com.comedown.experience.reports.application.FourWeekReport;
import com.comedown.experience.reports.application.FourWeekReportQuery;
import com.comedown.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/reports/four-week} → 200. 28일차 비교 리포트(HTTP 책임: experience.reports).
 * 온보딩 완료 후에만(403). 28일차 전·DAY28 CBI 전·동의 없음은 200 + available: false.
 */
@RestController
public class FourWeekReportController {

    private final FourWeekReportQuery query;

    public FourWeekReportController(FourWeekReportQuery query) {
        this.query = query;
    }

    @GetMapping("/api/reports/four-week")
    public FourWeekReport report(CurrentUser user) {
        return query.report(user.id());
    }
}
