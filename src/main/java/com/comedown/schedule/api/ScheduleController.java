package com.comedown.schedule.api;

import com.comedown.schedule.application.WeeklyLoadService;
import com.comedown.schedule.domain.WeekLoad;
import com.comedown.shared.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 주간 일정 부하 조회. HTTP 변환과 검증만 하고 계산·저장은 application에 맡긴다. */
@RestController
class ScheduleController {

    /** 한 번에 조회할 수 있는 최대 주 수(과도한 조회 방지용 입력 상한). */
    static final int MAX_WEEKS = 52;

    private final WeeklyLoadService service;

    ScheduleController(WeeklyLoadService service) {
        this.service = service;
    }

    @GetMapping("/api/schedule/weeks")
    WeeksResponse weeks(CurrentUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @Min(1) @Max(MAX_WEEKS) int count) {
        return new WeeksResponse(service.weeks(user.id(), from, count).stream().map(WeekLoadResponse::from).toList());
    }

    record WeeksResponse(List<WeekLoadResponse> weeks) {
    }

    record WeekLoadResponse(
            LocalDate weekStart,
            LocalDate weekEnd,
            java.math.BigDecimal totalHours,
            java.math.BigDecimal loadScore,
            java.math.BigDecimal vsUsualPct,
            int deadlineCount,
            int lateNightCount,
            int restDays,
            String riskLevel,
            List<String> riskReasons,
            String vsUsualUnavailableReason) {

        static WeekLoadResponse from(WeekLoad load) {
            return new WeekLoadResponse(load.weekStart(), load.weekStart().plusDays(6), load.totalHours(),
                    load.loadScore(), load.vsUsualPct(), load.deadlineCount(), load.lateNightCount(),
                    load.restDays(), load.riskLevel().name(),
                    load.riskReasons().stream().map(Enum::name).toList(),
                    load.vsUsualUnavailableReason() == null ? null : load.vsUsualUnavailableReason().name());
        }
    }
}
