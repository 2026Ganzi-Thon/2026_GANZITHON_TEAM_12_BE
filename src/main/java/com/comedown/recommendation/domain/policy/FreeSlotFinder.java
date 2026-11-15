package com.comedown.recommendation.domain.policy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 하루의 빈 시간 탐색(순수 계산). 수면 시간대는 제외하고, 앞뒤 일정과는 최소 간격을 둔다. 후보 시작 시각은 30분 단위로
 * 맞추고 이른 시각부터 돌려준다. 시간 일정만 막는 시간으로 보며 종일 일정은 호출하는 쪽이 넘기지 않는다.
 */
public final class FreeSlotFinder {

    static final Duration GRID = Duration.ofMinutes(30);

    private FreeSlotFinder() {
    }

    /** 반열린 구간 {@code [start, end)}. */
    public record Interval(Instant start, Instant end) {
        boolean overlaps(Interval o) {
            return start.isBefore(o.end) && o.start.isBefore(end);
        }
    }

    /**
     * @param day 서울 기준 날짜(그 날 안에서만 찾는다)
     * @param length 필요한 길이
     * @param busy 막힌 시간(일정). 앞뒤로 {@code minGap}만큼 더 막는다
     * @param notBefore 이 시각 이전은 쓰지 않는다(오늘을 찾을 때 현재 시각)
     * @param limit 최대 개수
     */
    public static List<Interval> find(LocalDate day, ZoneId zone, Duration length, List<Interval> busy,
            LocalTime sleepStart, LocalTime sleepEnd, Duration minGap, Instant notBefore, int limit) {
        Instant dayStart = day.atStartOfDay(zone).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
        List<Interval> blocked = new ArrayList<>();
        for (Interval b : busy) {
            blocked.add(new Interval(b.start().minus(minGap), b.end().plus(minGap)));
        }
        blocked.addAll(sleepBlocks(day, zone, sleepStart, sleepEnd));
        blocked.sort(Comparator.comparing(Interval::start));

        List<Interval> slots = new ArrayList<>();
        Instant candidate = alignUp(max(dayStart, notBefore), zone);
        while (slots.size() < limit && !candidate.plus(length).isAfter(dayEnd)) {
            Interval slot = new Interval(candidate, candidate.plus(length));
            Interval hit = firstOverlap(blocked, slot);
            if (hit == null) {
                slots.add(slot);
                candidate = candidate.plus(GRID);
            } else {
                candidate = alignUp(max(candidate.plus(GRID), hit.end()), zone);
            }
        }
        return slots;
    }

    /** 그 시간대가 정확히 빈 시간으로 들어갈 수 있는지(다른 시간 승인 검증용). */
    public static boolean fits(LocalDate day, ZoneId zone, Interval wanted, List<Interval> busy,
            LocalTime sleepStart, LocalTime sleepEnd, Duration minGap) {
        Instant dayStart = day.atStartOfDay(zone).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
        if (wanted.start().isBefore(dayStart) || wanted.end().isAfter(dayEnd)) {
            return false;
        }
        for (Interval b : busy) {
            if (wanted.overlaps(new Interval(b.start().minus(minGap), b.end().plus(minGap)))) {
                return false;
            }
        }
        return sleepBlocks(day, zone, sleepStart, sleepEnd).stream().noneMatch(wanted::overlaps);
    }

    /** 그 날 안에 드는 수면 시간대(자정을 넘는 수면은 두 조각). */
    static List<Interval> sleepBlocks(LocalDate day, ZoneId zone, LocalTime sleepStart, LocalTime sleepEnd) {
        Instant dayStart = day.atStartOfDay(zone).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
        Instant start = day.atTime(sleepStart).atZone(zone).toInstant();
        Instant end = day.atTime(sleepEnd).atZone(zone).toInstant();
        List<Interval> blocks = new ArrayList<>();
        if (sleepStart.isBefore(sleepEnd)) {
            blocks.add(new Interval(start, end));
        } else {
            blocks.add(new Interval(dayStart, end));
            blocks.add(new Interval(start, dayEnd));
        }
        return blocks;
    }

    private static Interval firstOverlap(List<Interval> blocked, Interval slot) {
        Interval latest = null;
        for (Interval b : blocked) {
            if (slot.overlaps(b) && (latest == null || b.end().isAfter(latest.end()))) {
                latest = b;
            }
        }
        return latest;
    }

    private static Instant alignUp(Instant instant, ZoneId zone) {
        LocalTime time = instant.atZone(zone).toLocalTime();
        long minutes = time.toSecondOfDay() / 60;
        long grid = GRID.toMinutes();
        boolean aligned = minutes % grid == 0 && time.getSecond() == 0 && time.getNano() == 0;
        if (aligned) {
            return instant;
        }
        long next = (minutes / grid + 1) * grid;
        return instant.atZone(zone).toLocalDate().atStartOfDay(zone).plusMinutes(next).toInstant();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
