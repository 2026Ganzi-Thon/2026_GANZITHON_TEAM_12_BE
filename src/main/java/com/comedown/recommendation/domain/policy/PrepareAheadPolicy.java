package com.comedown.recommendation.domain.policy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * PRE_START("미리 시작하기") 규칙. 순수 계산이며 값은 설정으로 바꿀 수 있다.
 * <ul>
 *   <li>대상: 위험(RISK) 주에 마감이 있는 <b>과제</b>(ASSIGNMENT) 일정. 지난 마감은 제외한다.</li>
 *   <li>제안: 마감 일정 하나당 하나. 마감 전날부터 하루씩 거슬러 올라가며(최대 {@link #LOOKBACK_DAYS}일, 오늘까지) 빈 시간이
 *       있는 첫 날에 {@code blockLength} 길이의 블록을 만든다.</li>
 *   <li>하루 제안(승인 예약 포함)은 최대 {@code maxPerDay}개.</li>
 *   <li>유효 기간: 제안 날짜가 지나기 전까지(다음 날 00:00 서울에 만료).</li>
 * </ul>
 */
public final class PrepareAheadPolicy {

    public static final String ASSIGNMENT_CATEGORY = "ASSIGNMENT";
    public static final String RISK_LEVEL = "RISK";
    public static final String REASON_CODE = "RISK_WEEK";
    public static final String CALCULATION_VERSION = "load-v1";
    public static final int LOOKBACK_DAYS = 7;
    public static final Duration MIN_GAP = Duration.ofMinutes(10);

    private final Duration blockLength;
    private final int maxPerDay;
    private final ZoneId zone;

    public PrepareAheadPolicy(Duration blockLength, int maxPerDay, ZoneId zone) {
        if (blockLength.isNegative() || blockLength.isZero() || maxPerDay < 1) {
            throw new IllegalArgumentException("blockLength and maxPerDay must be positive");
        }
        this.blockLength = blockLength;
        this.maxPerDay = maxPerDay;
        this.zone = zone;
    }

    public Duration blockLength() {
        return blockLength;
    }

    public int maxPerDay() {
        return maxPerDay;
    }

    /** 제안 블록을 찾아볼 날들: 마감 전날부터 오늘까지 거슬러 올라가는 순서(최대 7일). 마감이 오늘 이전이면 비어 있다. */
    public List<LocalDate> candidateDays(LocalDate today, LocalDate deadlineDate) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate earliest = deadlineDate.minusDays(LOOKBACK_DAYS);
        if (earliest.isBefore(today)) {
            earliest = today;
        }
        for (LocalDate day = deadlineDate.minusDays(1); !day.isBefore(earliest); day = day.minusDays(1)) {
            days.add(day);
        }
        return days;
    }

    /** 제안은 그 날짜가 지나면(다음 날 00:00 서울) 만료된다. */
    public Instant expiresAt(LocalDate targetDate) {
        return targetDate.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /**
     * 규칙 기반 카드 문구(AI 도입 전). 위험 주에 마감이 몇 건 몰렸는지 알리고, 마감은 옮기지 않고 준비 시간만 넣는다는
     * 점을 밝힌다(앱은 일정을 추가만 한다). 일정 제목은 저장하지 않으므로 넣지 않는다.
     *
     * @param thisWeekStart 오늘이 속한 주의 월요일
     * @param riskWeekStart 마감이 있는 위험 주의 월요일
     * @param deadlineCount 그 주의 마감 수(주간 부하의 deadline_count)
     */
    public String message(LocalDate thisWeekStart, LocalDate riskWeekStart, int deadlineCount) {
        String week = weekLabel(thisWeekStart, riskWeekStart);
        String lead = deadlineCount >= 2
                ? week + "에 마감 " + deadlineCount + "건이 몰려 있어요."
                : week + "에 일정이 많이 몰려 있어요.";
        return lead + " 과제를 " + blockLength.toMinutes() + "분만 미리 시작해 볼까요? (마감일은 그대로예요)";
    }

    private static String weekLabel(LocalDate thisWeekStart, LocalDate riskWeekStart) {
        long weeksAhead = ChronoUnit.WEEKS.between(thisWeekStart, riskWeekStart);
        if (weeksAhead <= 0) {
            return "이번 주";
        }
        if (weeksAhead == 1) {
            return "다음 주";
        }
        return riskWeekStart.getMonthValue() + "월 " + riskWeekStart.getDayOfMonth() + "일 주";
    }

    public boolean isTarget(String category) {
        return ASSIGNMENT_CATEGORY.equals(category);
    }

    public boolean isRiskWeek(String riskLevel) {
        return RISK_LEVEL.equals(riskLevel);
    }
}
