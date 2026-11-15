package com.comedown.schedule.domain;

import com.comedown.schedule.domain.WeekLoad.RiskLevel;
import com.comedown.schedule.domain.WeekLoad.RiskReason;
import com.comedown.schedule.domain.WeekLoad.UnavailableReason;
import com.comedown.shared.time.ServiceTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 일정 부하·위험 주차 계산({@code load-v1}). 산식은 docs/계산_정책.md 1절을 따르는 순수 계산이다.
 * 시간 규칙은 서울 기준이며 DB·Clock·Spring에 의존하지 않는다.
 *
 * <p>연속 일정일은 주 경계를 넘어 계산하므로, 호출하는 쪽은 대상 주 앞뒤로 충분한 범위의 일정을 넘긴다.
 * 넘긴 일정만 보고 계산한다.
 */
public final class LoadCalculator {

    public static final String VERSION = "load-v1";

    private static final ZoneId ZONE = ServiceTime.ZONE;
    private static final LocalTime NIGHT_END = LocalTime.of(7, 0);
    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);
    private static final double HEAVY_FACTOR = 1.5;
    private static final double NIGHT_FACTOR = 1.5;
    private static final double WEEKEND_FACTOR = 1.5;
    private static final int PRIOR_WEEKS = 4;
    private static final int MIN_PRIOR_DATA_WEEKS = 2;

    private LoadCalculator() {
    }

    /**
     * @param weekStart 대상 주의 월요일
     * @param events 대상 주와 그 앞뒤의 일정
     * @param priorWeeks 직전 4주의 부하(순서 무관, 정확히 4개)
     */
    public static WeekLoad calculate(LocalDate weekStart, List<LoadEvent> events, List<PriorWeek> priorWeeks) {
        if (weekStart.getDayOfWeek() != ServiceTime.WEEK_START) {
            throw new IllegalArgumentException("weekStart must be a Monday: " + weekStart);
        }
        return calculateWindow(weekStart, events, priorWeeks);
    }

    /**
     * 시작 요일과 무관하게 {@code windowStart}부터 7일 구간의 부하를 계산한다. 온보딩 기준 n주차 같은
     * 월~일이 아닌 주에 쓴다. 산식은 {@link #calculate}와 같고, 결과의 {@code weekStart}는 구간 시작일이다.
     */
    public static WeekLoad calculateWindow(LocalDate weekStart, List<LoadEvent> events, List<PriorWeek> priorWeeks) {
        if (priorWeeks.size() != PRIOR_WEEKS) {
            throw new IllegalArgumentException("priorWeeks must have " + PRIOR_WEEKS + " entries");
        }
        LocalDate weekEndExclusive = weekStart.plusDays(7);
        List<LoadEvent> counted = events.stream().filter(LoadEvent::isCounted).toList();
        List<LoadEvent> timed = counted.stream().filter(e -> !e.allDay()).toList();

        Hours hours = weightedHours(weekStart, weekEndExclusive, timed);
        BigDecimal loadScore = round1(hours.weightedSeconds / 3600.0);

        int deadlineCount = 0;
        int lateNightCount = 0;
        for (LoadEvent event : counted) {
            LocalDate eventDate = event.allDay() ? event.startDate() : toDate(event.start());
            if (eventDate.isBefore(weekStart) || !eventDate.isBefore(weekEndExclusive)) {
                continue;
            }
            if (event.isDeadlineEvent()) {
                deadlineCount++;
            }
            if (!event.allDay() && isLateNight(event)) {
                lateNightCount++;
            }
        }

        // 종일 일정은 시간에 넣지 않으므로 쉬는 날·연속 일정일 계산에서도 일정 없는 날로 본다(정책 Q9 임시 해석).
        TreeSet<LocalDate> eventDays = eventDays(timed);
        int restDays = 0;
        for (LocalDate day = weekStart; day.isBefore(weekEndExclusive); day = day.plusDays(1)) {
            if (!eventDays.contains(day)) {
                restDays++;
            }
        }
        int longestStreak = longestStreakTouching(eventDays, weekStart, weekEndExclusive);

        BigDecimal vsUsualPct = null;
        UnavailableReason unavailable = null;
        long dataWeeks = priorWeeks.stream().filter(PriorWeek::hasData).count();
        if (dataWeeks < MIN_PRIOR_DATA_WEEKS) {
            unavailable = UnavailableReason.INSUFFICIENT_DATA;
        } else {
            BigDecimal average = priorWeeks.stream().map(PriorWeek::loadScore)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(PRIOR_WEEKS), 6, RoundingMode.HALF_UP);
            if (average.signum() == 0) {
                unavailable = UnavailableReason.ZERO_BASELINE;
            } else {
                vsUsualPct = loadScore.divide(average, 6, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP);
            }
        }

        List<RiskReason> reasons = new ArrayList<>();
        int score = 0;
        int loadPoints = loadHighPoints(vsUsualPct);
        if (loadPoints > 0) {
            reasons.add(RiskReason.LOAD_HIGH);
            score += loadPoints;
        }
        int deadlinePoints = deadlineCount >= 3 ? 2 : deadlineCount == 2 ? 1 : 0;
        if (deadlinePoints > 0) {
            reasons.add(RiskReason.DEADLINES);
            score += deadlinePoints;
        }
        if (restDays == 0) {
            reasons.add(RiskReason.NO_REST_DAY);
            score += 1;
        }
        if (lateNightCount >= 3) {
            reasons.add(RiskReason.LATE_NIGHT);
            score += 1;
        }
        if (longestStreak >= 10) {
            reasons.add(RiskReason.LONG_STREAK);
            score += 1;
        }
        RiskLevel level = score >= 4 ? RiskLevel.RISK : score >= 2 ? RiskLevel.WATCH : RiskLevel.NONE;

        return new WeekLoad(weekStart, round1(hours.totalSeconds / 3600.0), loadScore, vsUsualPct, unavailable,
                deadlineCount, lateNightCount, round1(hours.weekendSeconds / 3600.0), restDays, level,
                List.copyOf(reasons), VERSION);
    }

    /**
     * 임의 기간 {@code [from, toExclusive)}의 요약(일정 리포트의 "지난 한 달"). 시간·쉬는 날·늦은 밤·마감 집계 규칙은
     * 주간 계산과 같고, 부하 점수·위험 판정·평소 대비는 내지 않는다.
     */
    public static PeriodSummary summarize(LocalDate from, LocalDate toExclusive, List<LoadEvent> events) {
        List<LoadEvent> counted = events.stream().filter(LoadEvent::isCounted).toList();
        List<LoadEvent> timed = counted.stream().filter(e -> !e.allDay()).toList();
        Hours hours = weightedHours(from, toExclusive, timed);
        int deadlineCount = 0;
        int lateNightCount = 0;
        int eventCount = 0;
        for (LoadEvent event : counted) {
            LocalDate eventDate = event.allDay() ? event.startDate() : toDate(event.start());
            if (eventDate.isBefore(from) || !eventDate.isBefore(toExclusive)) {
                continue;
            }
            eventCount++;
            if (event.isDeadlineEvent()) {
                deadlineCount++;
            }
            if (!event.allDay() && isLateNight(event)) {
                lateNightCount++;
            }
        }
        TreeSet<LocalDate> eventDays = eventDays(timed);
        int restDays = 0;
        for (LocalDate day = from; day.isBefore(toExclusive); day = day.plusDays(1)) {
            if (!eventDays.contains(day)) {
                restDays++;
            }
        }
        return new PeriodSummary(from, toExclusive.minusDays(1), round1(hours.totalSeconds / 3600.0),
                round1(hours.weekendSeconds / 3600.0), deadlineCount, lateNightCount, restDays, eventCount);
    }

    private static int loadHighPoints(BigDecimal vsUsualPct) {
        // vs_usual이 null이면 0점(정책 Q10 임시 해석).
        if (vsUsualPct == null) {
            return 0;
        }
        if (vsUsualPct.compareTo(BigDecimal.valueOf(30)) >= 0) {
            return 2;
        }
        return vsUsualPct.compareTo(BigDecimal.valueOf(15)) >= 0 ? 1 : 0;
    }

    private record Segment(Instant start, Instant end, double weight) {
    }

    private static final class Hours {
        double totalSeconds;
        double weightedSeconds;
        double weekendSeconds;
    }

    /** 겹친 시간은 한 번만 세고, 겹친 구간에는 높은 가중치를 쓴다. */
    private static Hours weightedHours(LocalDate weekStart, LocalDate weekEndExclusive, List<LoadEvent> timed) {
        Instant weekFrom = weekStart.atStartOfDay(ZONE).toInstant();
        Instant weekTo = weekEndExclusive.atStartOfDay(ZONE).toInstant();
        List<Segment> segments = new ArrayList<>();
        for (LoadEvent event : timed) {
            Instant from = max(event.start(), weekFrom);
            Instant to = min(event.end(), weekTo);
            double base = event.category() != null && event.category().isHeavy() ? HEAVY_FACTOR : 1.0;
            Instant cursor = from;
            while (cursor.isBefore(to)) {
                Instant next = min(nextBoundary(cursor), to);
                segments.add(new Segment(cursor, next, base * timeFactor(cursor)));
                cursor = next;
            }
        }
        TreeSet<Instant> points = new TreeSet<>();
        for (Segment s : segments) {
            points.add(s.start());
            points.add(s.end());
        }
        Hours hours = new Hours();
        Instant previous = null;
        for (Instant point : points) {
            if (previous != null) {
                double weight = 0;
                boolean covered = false;
                for (Segment s : segments) {
                    if (!s.start().isAfter(previous) && !s.end().isBefore(point)) {
                        covered = true;
                        weight = Math.max(weight, s.weight());
                    }
                }
                if (covered) {
                    double seconds = Duration.between(previous, point).getSeconds();
                    hours.totalSeconds += seconds;
                    hours.weightedSeconds += seconds * weight;
                    if (isWeekend(previous)) {
                        hours.weekendSeconds += seconds;
                    }
                }
            }
            previous = point;
        }
        return hours;
    }

    /** 22시~07시, 토·일 구간 가중치. 두 조건이 겹치면 곱한다. */
    private static double timeFactor(Instant instant) {
        ZonedDateTime local = instant.atZone(ZONE);
        LocalTime time = local.toLocalTime();
        double factor = 1.0;
        if (time.isBefore(NIGHT_END) || !time.isBefore(NIGHT_START)) {
            factor *= NIGHT_FACTOR;
        }
        if (isWeekend(instant)) {
            factor *= WEEKEND_FACTOR;
        }
        return factor;
    }

    private static boolean isWeekend(Instant instant) {
        DayOfWeek day = instant.atZone(ZONE).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    /** 가중치가 바뀌는 다음 서울 시각 경계(07:00, 22:00, 자정). */
    private static Instant nextBoundary(Instant instant) {
        ZonedDateTime local = instant.atZone(ZONE);
        LocalDate date = local.toLocalDate();
        for (ZonedDateTime candidate : List.of(
                date.atTime(NIGHT_END).atZone(ZONE),
                date.atTime(NIGHT_START).atZone(ZONE),
                date.plusDays(1).atStartOfDay(ZONE))) {
            if (candidate.toInstant().isAfter(instant)) {
                return candidate.toInstant();
            }
        }
        throw new IllegalStateException("no boundary after " + instant);
    }

    /** 22시 이후 끝나거나 07시 이전에 시작하는지(시작일 기준 서울 시각). */
    private static boolean isLateNight(LoadEvent event) {
        LocalDate startDate = toDate(event.start());
        boolean endsLate = event.end().isAfter(startDate.atTime(NIGHT_START).atZone(ZONE).toInstant());
        boolean startsEarly = event.start().isBefore(startDate.atTime(NIGHT_END).atZone(ZONE).toInstant());
        return endsLate || startsEarly;
    }

    private static TreeSet<LocalDate> eventDays(List<LoadEvent> timed) {
        TreeSet<LocalDate> days = new TreeSet<>();
        for (LoadEvent event : timed) {
            LocalDate first = toDate(event.start());
            LocalDate last = toDate(event.end().minusNanos(1));
            for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                days.add(day);
            }
        }
        return days;
    }

    /** 대상 주의 날을 하나라도 포함하는 연속 일정일 구간 중 가장 긴 길이(주 경계를 넘어 센다). */
    private static int longestStreakTouching(Set<LocalDate> days, LocalDate weekStart, LocalDate weekEndExclusive) {
        int longest = 0;
        for (LocalDate day = weekStart; day.isBefore(weekEndExclusive); day = day.plusDays(1)) {
            if (!days.contains(day)) {
                continue;
            }
            LocalDate from = day;
            while (days.contains(from.minusDays(1))) {
                from = from.minusDays(1);
            }
            LocalDate to = day;
            while (days.contains(to.plusDays(1))) {
                to = to.plusDays(1);
            }
            longest = Math.max(longest, (int) (to.toEpochDay() - from.toEpochDay() + 1));
        }
        return longest;
    }

    private static LocalDate toDate(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static BigDecimal round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }
}
