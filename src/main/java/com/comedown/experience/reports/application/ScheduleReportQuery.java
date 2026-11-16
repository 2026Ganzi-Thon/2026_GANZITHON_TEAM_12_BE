package com.comedown.experience.reports.application;

import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.calendar.contract.CalendarOverview;
import com.comedown.calendar.contract.CalendarOverviewQuery;
import com.comedown.schedule.contract.PeriodLoad;
import com.comedown.schedule.contract.PeriodLoadQuery;
import com.comedown.schedule.contract.WeekLoadView;
import com.comedown.schedule.contract.WeekLoads;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 일정 리포트 조합. calendar·schedule·account 읽기 계약의 스냅샷만 쓰고 직접 저장하지 않는다.
 * <p>
 * 구현 해석(확인 필요): 지난 한 달 = [오늘 − 1개월, 어제], enoughData = 그 기간에 집계 대상 일정이 1건 이상.
 * <p>
 * 구현 해석(확인 필요): 마지막 동기화 시각을 따로 저장하는 곳이 없어 {@code syncedAt}은 저장된 일정의 마지막 동기화 시각과
 * 응답에 싣는 주(이번 주 + 다음 4주)의 주간 부하 계산 시각 중 늦은 쪽으로 한다. 동기화는 일정이 0건이어도 주간 부하를
 * 저장하므로, 주간 부하를 내려주는 응답의 {@code syncedAt}은 null이 아니다.
 */
@Service
public class ScheduleReportQuery {

    static final int UPCOMING_WEEKS = 4;

    private final CalendarConnectionQuery connection;
    private final CalendarOverviewQuery overview;
    private final PeriodLoadQuery periods;
    private final WeekLoads weekLoads;
    private final ServiceTime time;

    public ScheduleReportQuery(CalendarConnectionQuery connection, CalendarOverviewQuery overview,
            PeriodLoadQuery periods, WeekLoads weekLoads, ServiceTime time) {
        this.connection = connection;
        this.overview = overview;
        this.periods = periods;
        this.weekLoads = weekLoads;
        this.time = time;
    }

    public ScheduleReport report(long userId) {
        if (!connection.isConnected(userId)) {
            throw new ApiException(ErrorCode.CALENDAR_NOT_CONNECTED);
        }
        LocalDate today = time.today();
        LocalDate thisMonday = time.weekStartOf(today);
        CalendarOverview o = overview.overview(userId);
        PeriodLoad month = periods.summarize(userId, today.minusMonths(1), today.minusDays(1));
        WeekLoadView current = weekLoads.find(userId, thisMonday).orElse(null);
        List<WeekLoadView> upcoming = weekLoads.range(userId, thisMonday.plusWeeks(1), UPCOMING_WEEKS);
        Instant syncedAt = later(o.syncedAt(),
                weekLoads.lastComputedAt(userId, thisMonday, 1 + UPCOMING_WEEKS).orElse(null));
        return new ScheduleReport(
                time.toResponse(syncedAt),
                new ScheduleReport.Classification(o.total(), o.classified(), o.pending()),
                new ScheduleReport.PastMonth(month.from(), month.to(), month.restDays(), month.lateNightCount(),
                        month.totalHours(), month.weekendHours(), month.deadlineCount()),
                current == null ? null
                        : new ScheduleReport.ThisWeek(current.weekStart(), current.vsUsualPct(), current.riskLevel()),
                upcoming, month.eventCount() > 0);
    }

    /** 둘 중 늦은 시각. 한쪽이 없으면 다른 쪽, 둘 다 없으면 null. */
    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
