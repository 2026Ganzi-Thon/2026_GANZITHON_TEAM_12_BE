package com.comedown.experience.reports.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.checkin.contract.DayIndex;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.recovery.contract.RecoveryFallbackText;
import com.comedown.recovery.contract.RecoveryQuery;
import com.comedown.recovery.contract.RecoveryRange;
import com.comedown.schedule.contract.ProgramWeekLoad;
import com.comedown.schedule.contract.ProgramWeekLoadQuery;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 회복 리포트(C1) 조합. recovery 읽기 계약의 스냅샷만 쓰고 계산·저장하지 않는다.
 * <p>
 * 그래프의 주는 온보딩 기준 n주차이며, 진행 중인 주차까지 포함한 최근 n개 주차를 보여준다({@link ProgramWeeks}).
 * 일정 부하는 schedule의 {@link ProgramWeekLoadQuery}로 읽는다. 일정축 구현 전(bean 없음)·캘린더 미연결이면 부하 값은 null이다.
 * <p>
 * 아직 비어 있는 값: rangePosition.load(schedule 계약), AI 해석(규칙 기반 문구로 대체).
 */
@Service
public class RecoveryReportQuery {

    static final int REQUIRED_DAYS = 7;
    static final int RANGE_DAY_INDEX = 28;
    static final String CONSENT_REQUIRED = "CONSENT_REQUIRED";
    static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

    private final AccountAccessQuery accountAccess;
    private final RecoveryQuery recoveryQuery;
    private final ServiceTime serviceTime;
    private final ObjectProvider<ProgramWeekLoadQuery> weekLoads;

    public RecoveryReportQuery(AccountAccessQuery accountAccess, RecoveryQuery recoveryQuery,
            ServiceTime serviceTime, ObjectProvider<ProgramWeekLoadQuery> weekLoads) {
        this.accountAccess = accountAccess;
        this.recoveryQuery = recoveryQuery;
        this.serviceTime = serviceTime;
        this.weekLoads = weekLoads;
    }

    /** 온보딩 완료 후에만(403). */
    public HealthDataAccess requireOnboarded(long userId) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!access.onboardingCompleted()) {
            throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
        }
        return access;
    }

    /** @param days 그래프 일수(7~90, 호출하는 쪽에서 검증). 7일 단위로 올림한 주차 수만큼 보여준다 */
    public RecoveryReport report(HealthDataAccess access, int days) {
        long userId = access.userId();
        LocalDate today = serviceTime.today();
        Optional<DailyRecoverySnapshot> latest = recoveryQuery.findLatestOnOrBefore(userId, today);
        int validDays = latest.map(DailyRecoverySnapshot::baselineValidDays).orElse(0);
        RecoveryReport.Unlock unlock = new RecoveryReport.Unlock(REQUIRED_DAYS, validDays);

        if (!access.sensitiveHrvConsented()) {
            return locked(CONSENT_REQUIRED, unlock);
        }
        if (latest.isEmpty() || !latest.get().baselineEstablished()) {
            return locked(null, unlock);
        }
        DailyRecoverySnapshot current = latest.get();
        LocalDate onboardingDate = serviceTime.toServiceDate(access.onboardingCompletedAt());
        ProgramWeeks weeks = ProgramWeeks.recent(onboardingDate, today, days);
        List<DailyRecoverySnapshot> states = recoveryQuery.findRange(userId, weeks.from(), today);
        Map<Integer, ProgramWeekLoad> loads = findLoads(userId, weeks);

        RecoveryReport.Graph graph = new RecoveryReport.Graph(
                weeks.from(), weeks.to(),
                states.stream().filter(s -> s.dailyLnRmssd() != null)
                        .map(s -> new RecoveryReport.Point(s.date(), round1(Math.exp(s.dailyLnRmssd())),
                                s.confounded(), s.qualityLevel()))
                        .toList(),
                states.stream().filter(s -> s.trendLn() != null)
                        .map(s -> new RecoveryReport.TrendPoint(s.date(), round1(s.trendRmssdMs())))
                        .toList(),
                states.stream().filter(DailyRecoverySnapshot::baselineEstablished)
                        .map(s -> new RecoveryReport.Band(s.date(), round1(s.normalLowerMs()),
                                round1(s.normalUpperMs())))
                        .toList(),
                new RecoveryReport.Range(round1(current.normalLowerMs()), round1(current.normalUpperMs())),
                loadWeeks(weeks, loads));

        String latestQuality = states.stream()
                .filter(s -> s.qualityLevel() != null)
                .max(Comparator.comparing(DailyRecoverySnapshot::date))
                .map(DailyRecoverySnapshot::qualityLevel)
                .orElse(null);

        return new RecoveryReport(false, null, unlock, graph, current.changePct(), current.recoveryStatus(),
                latestQuality, loadVsUsualPct(weeks, loads),
                interpretation(current), rangePosition(access, userId, today));
    }

    /** 범위 안 주차별 부하. 일정축 구현 전·캘린더 미연결이면 빈 맵. */
    private Map<Integer, ProgramWeekLoad> findLoads(long userId, ProgramWeeks weeks) {
        ProgramWeekLoadQuery query = weekLoads.getIfAvailable();
        if (query == null) {
            return Map.of();
        }
        return query.find(userId, weeks.onboardingDate(), weeks.fromWeek(), weeks.toWeek())
                .map(list -> list.stream().collect(Collectors.toMap(ProgramWeekLoad::week, Function.identity())))
                .orElse(Map.of());
    }

    private static List<RecoveryReport.LoadWeek> loadWeeks(ProgramWeeks weeks, Map<Integer, ProgramWeekLoad> loads) {
        List<RecoveryReport.LoadWeek> result = new ArrayList<>();
        for (int week = weeks.fromWeek(); week <= weeks.toWeek(); week++) {
            ProgramWeekLoad load = loads.get(week);
            Double score = load == null || load.loadScore() == null ? null : round1(load.loadScore().doubleValue());
            result.add(new RecoveryReport.LoadWeek(week, weeks.startOf(week), weeks.endOf(week),
                    week == weeks.currentWeek(), score));
        }
        return result;
    }

    private static BigDecimal loadVsUsualPct(ProgramWeeks weeks, Map<Integer, ProgramWeekLoad> loads) {
        ProgramWeekLoad current = loads.get(weeks.currentWeek());
        return current == null ? null : current.vsUsualPct();
    }

    private RecoveryReport.RangePosition rangePosition(HealthDataAccess access, long userId, LocalDate today) {
        LocalDate onboardingDate = serviceTime.toServiceDate(access.onboardingCompletedAt());
        LocalDate availableFrom = onboardingDate.plusDays(RANGE_DAY_INDEX - 1L);
        RecoveryRange range = recoveryQuery.rangePosition(userId, today);
        boolean available = DayIndex.of(onboardingDate, today) >= RANGE_DAY_INDEX && range.enoughSamples();
        if (!available) {
            return new RecoveryReport.RangePosition(false, availableFrom, null, null, null, null);
        }
        RecoveryReport.RangeValue recovery = range.position() == null ? null : new RecoveryReport.RangeValue(
                Math.round(range.position()), round1(Math.exp(range.lowLn())), round1(Math.exp(range.highLn())));
        return new RecoveryReport.RangePosition(true, availableFrom, recovery, null,
                recovery == null ? INSUFFICIENT_DATA : null, INSUFFICIENT_DATA);
    }

    /** AI 도입 전 규칙 기반 해석 문구(recovery 소유 문구). */
    static RecoveryReport.Interpretation interpretation(DailyRecoverySnapshot current) {
        return new RecoveryReport.Interpretation(
                RecoveryFallbackText.of(current.recoveryStatus(), current.changePct()), false, true);
    }

    private static RecoveryReport locked(String reason, RecoveryReport.Unlock unlock) {
        return new RecoveryReport(true, reason, unlock, null, null, null, null, null, null, null);
    }

    static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
