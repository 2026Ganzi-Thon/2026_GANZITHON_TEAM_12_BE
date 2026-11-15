package com.comedown.experience.reports.api;

import com.comedown.experience.reports.application.ScheduleReport;
import com.comedown.experience.reports.application.ScheduleReportQuery;
import com.comedown.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/schedule/report} → 200. 일정 리포트(A5, HTTP 책임: experience.reports).
 * 온보딩 중에도 조회할 수 있고, 캘린더가 연결되지 않았으면 409 CALENDAR_NOT_CONNECTED.
 */
@RestController
public class ScheduleReportController {

    private final ScheduleReportQuery query;

    public ScheduleReportController(ScheduleReportQuery query) {
        this.query = query;
    }

    @GetMapping("/api/schedule/report")
    public ScheduleReport report(CurrentUser user) {
        return query.report(user.id());
    }
}
