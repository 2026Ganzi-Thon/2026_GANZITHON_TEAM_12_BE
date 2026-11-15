package com.comedown.schedule.application;

import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.calendar.contract.CalendarEventReader;
import com.comedown.schedule.contract.ProgramWeekLoad;
import com.comedown.schedule.contract.ProgramWeekLoadQuery;
import com.comedown.schedule.domain.LoadCalculator;
import com.comedown.schedule.domain.LoadEvent;
import com.comedown.schedule.domain.PriorWeek;
import com.comedown.schedule.domain.WeekLoad;
import com.comedown.shared.time.InstantRange;
import com.comedown.shared.time.ServiceTime;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 온보딩 기준 n주차 부하(리포트 전용). 월~일 주({@code weekly_loads})와 달리 저장하지 않고
 * 일정 원본으로 매번 계산한다(계산 정책 1절 "온보딩 기준 주차 부하").
 */
@Service
public class ProgramWeekLoadService implements ProgramWeekLoadQuery {

    private static final int PRIOR_WEEKS = 4;
    private static final int PADDING_DAYS = 10;
    private static final List<PriorWeek> NO_HISTORY = List.of(
            new PriorWeek(BigDecimal.ZERO, false), new PriorWeek(BigDecimal.ZERO, false),
            new PriorWeek(BigDecimal.ZERO, false), new PriorWeek(BigDecimal.ZERO, false));

    private final CalendarConnectionQuery connection;
    private final CalendarEventReader events;
    private final ServiceTime time;

    public ProgramWeekLoadService(CalendarConnectionQuery connection, CalendarEventReader events, ServiceTime time) {
        this.connection = connection;
        this.events = events;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<List<ProgramWeekLoad>> find(long userId, LocalDate onboardingDate, int fromWeek, int toWeek) {
        if (fromWeek < 1 || toWeek < fromWeek) {
            throw new IllegalArgumentException("invalid week range: " + fromWeek + ".." + toWeek);
        }
        if (!connection.isConnected(userId)) {
            return Optional.empty();
        }
        // 비교 대상(직전 4개 주차, 0주차 이하 포함)까지 한 번에 읽어 오래된 주차부터 계산한다.
        int firstWeek = fromWeek - PRIOR_WEEKS;
        InstantRange range = time.dateRange(
                windowStart(onboardingDate, firstWeek).minusDays(PADDING_DAYS),
                windowStart(onboardingDate, toWeek).plusDays(6L + PADDING_DAYS));
        List<LoadEvent> loadEvents = events.findOverlapping(userId, range).stream()
                .map(LoadEventMapper::toLoadEvent).toList();

        List<WeekLoad> computed = new ArrayList<>();
        List<ProgramWeekLoad> result = new ArrayList<>();
        for (int week = firstWeek; week <= toWeek; week++) {
            LocalDate start = windowStart(onboardingDate, week);
            List<PriorWeek> priors = week >= fromWeek ? priorsOf(computed) : NO_HISTORY;
            WeekLoad load = LoadCalculator.calculateWindow(start, loadEvents, priors);
            computed.add(load);
            if (week >= fromWeek) {
                result.add(new ProgramWeekLoad(week, start, start.plusDays(6), load.loadScore(), load.vsUsualPct(),
                        load.vsUsualUnavailableReason() == null ? null : load.vsUsualUnavailableReason().name()));
            }
        }
        return Optional.of(List.copyOf(result));
    }

    /** n주차 시작일 = 온보딩 날짜 + 7(n−1)일. 0주차 이하는 온보딩 전으로 거슬러 간다. */
    private static LocalDate windowStart(LocalDate onboardingDate, int week) {
        return onboardingDate.plusDays(7L * (week - 1));
    }

    /** 방금까지 계산한 주차 중 직전 4개. 집계 대상 일정이 있던 주만 자료 있는 주로 본다(정책 Q8 임시 해석). */
    private static List<PriorWeek> priorsOf(List<WeekLoad> computed) {
        List<PriorWeek> priors = new ArrayList<>();
        for (WeekLoad previous : computed.subList(computed.size() - PRIOR_WEEKS, computed.size())) {
            priors.add(new PriorWeek(previous.loadScore(), previous.totalHours().signum() > 0));
        }
        return priors;
    }
}
