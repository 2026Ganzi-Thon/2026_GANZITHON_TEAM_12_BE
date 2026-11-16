package com.comedown.schedule.application;

import com.comedown.calendar.contract.CalendarEventReader;
import com.comedown.schedule.domain.LoadCalculator;
import com.comedown.schedule.domain.LoadEvent;
import com.comedown.schedule.domain.PriorWeek;
import com.comedown.schedule.domain.WeekLoad;
import com.comedown.shared.time.InstantRange;
import com.comedown.shared.time.ServiceTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주간 부하 재계산·조회 유스케이스. 일정은 calendar 계약으로만 읽고 weekly_loads만 쓴다.
 * 외부 호출이 없으므로 DB 트랜잭션 하나로 묶는다.
 */
@Service
public class WeeklyLoadService {

    /** 연속 일정일 10일 판정에 필요한 앞뒤 여유(10일 연속이면 주의 앞뒤로 최대 9일). */
    private static final int STREAK_PADDING_DAYS = 10;
    private static final int PRIOR_WEEKS = 4;

    private final CalendarEventReader events;
    private final WeeklyLoadStore store;
    private final ServiceTime time;

    public WeeklyLoadService(CalendarEventReader events, WeeklyLoadStore store, ServiceTime time) {
        this.events = events;
        this.store = store;
        this.time = time;
    }

    /**
     * 주어진 주들을 오래된 주부터 다시 계산해 저장한다. 앞 주의 결과가 뒤 주의 "직전 4주"에 쓰이므로 순서가 중요하다.
     *
     * @param weekStarts 월요일 날짜들(순서·중복 무관)
     * @return 계산한 주(오름차순)
     */
    @Transactional
    public List<WeekLoad> recalculate(long userId, Iterable<LocalDate> weekStarts) {
        List<LocalDate> ordered = new ArrayList<>();
        for (LocalDate weekStart : weekStarts) {
            if (!ordered.contains(weekStart)) {
                ordered.add(weekStart);
            }
        }
        ordered.sort(null);
        List<WeekLoad> result = new ArrayList<>();
        for (LocalDate weekStart : ordered) {
            result.add(recalculateWeek(userId, weekStart));
        }
        return result;
    }

    /** 저장된 주간 부하를 {@code from}이 속한 주부터 {@code count}주 돌려준다. 계산된 적 없는 주는 빠진다. */
    @Transactional(readOnly = true)
    public List<WeekLoad> weeks(long userId, LocalDate from, int count) {
        LocalDate first = time.weekStartOf(from);
        return store.findBetween(userId, first, first.plusWeeks(count - 1L));
    }

    /** {@code from}이 속한 주부터 {@code count}주의 저장된 부하 중 가장 늦게 계산한 시각. 저장된 주가 없으면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<Instant> lastComputedAt(long userId, LocalDate from, int count) {
        LocalDate first = time.weekStartOf(from);
        return store.lastComputedAt(userId, first, first.plusWeeks(count - 1L));
    }

    private WeekLoad recalculateWeek(long userId, LocalDate weekStart) {
        InstantRange range = time.dateRange(
                weekStart.minusDays(STREAK_PADDING_DAYS), weekStart.plusDays(6L + STREAK_PADDING_DAYS));
        List<LoadEvent> loadEvents = events.findOverlapping(userId, range).stream()
                .map(LoadEventMapper::toLoadEvent)
                .toList();
        WeekLoad load = LoadCalculator.calculate(weekStart, loadEvents, priorWeeks(userId, weekStart));
        store.save(userId, load, time.now());
        return load;
    }

    /** 저장된 직전 4주. 행이 없거나 집계 대상 일정 시간이 0이면 "데이터 없는 주"로 본다(정책 Q8 임시 해석). */
    private List<PriorWeek> priorWeeks(long userId, LocalDate weekStart) {
        List<PriorWeek> prior = new ArrayList<>();
        for (int i = 1; i <= PRIOR_WEEKS; i++) {
            prior.add(store.find(userId, weekStart.minusWeeks(i))
                    .map(w -> new PriorWeek(w.loadScore(), w.totalHours().signum() > 0))
                    .orElse(new PriorWeek(BigDecimal.ZERO, false)));
        }
        return prior;
    }
}
