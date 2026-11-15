package com.comedown.experience.reports.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.checkin.contract.DayIndex;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.recovery.contract.RecoveryQuery;
import com.comedown.recovery.contract.RecoveryRange;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 회복 리포트(C1) 조합. recovery 읽기 계약의 스냅샷만 쓰고 계산·저장하지 않는다.
 * <p>
 * 아직 비어 있는 값: loadWeeks·loadVsUsualPct·rangePosition.load(schedule 계약), AI 해석(규칙 기반 문구로 대체).
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

    public RecoveryReportQuery(AccountAccessQuery accountAccess, RecoveryQuery recoveryQuery,
            ServiceTime serviceTime) {
        this.accountAccess = accountAccess;
        this.recoveryQuery = recoveryQuery;
        this.serviceTime = serviceTime;
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

    /** @param days 오늘 포함 그래프 일수(7~90, 호출하는 쪽에서 검증) */
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
        LocalDate from = today.minusDays(days - 1L);
        List<DailyRecoverySnapshot> states = recoveryQuery.findRange(userId, from, today);

        RecoveryReport.Graph graph = new RecoveryReport.Graph(
                from, today,
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
                List.of());

        String latestQuality = states.stream()
                .filter(s -> s.qualityLevel() != null)
                .max(Comparator.comparing(DailyRecoverySnapshot::date))
                .map(DailyRecoverySnapshot::qualityLevel)
                .orElse(null);

        return new RecoveryReport(false, null, unlock, graph, current.changePct(), latestQuality, null,
                interpretation(current), rangePosition(access, userId, today));
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

    /** AI 도입 전 규칙 기반 해석 문구(사실만 전달, 진단·권고 없음). */
    static RecoveryReport.Interpretation interpretation(DailyRecoverySnapshot current) {
        String status = switch (current.recoveryStatus()) {
            case "LOW" -> "최근 7일 회복이 평소 범위보다 낮아요.";
            case "NORMAL" -> "최근 7일 회복이 평소 범위 안에 있어요.";
            default -> "최근 7일 측정이 적어 평소와 비교하기 어려워요.";
        };
        BigDecimal pct = current.changePct();
        String change = "";
        if (pct != null) {
            if (pct.signum() == 0) {
                change = " 평소와 비슷해요.";
            } else {
                change = " 평소보다 " + pct.abs().stripTrailingZeros().toPlainString() + "% "
                        + (pct.signum() > 0 ? "높아요." : "낮아요.");
            }
        }
        return new RecoveryReport.Interpretation(status + change, false, true);
    }

    private static RecoveryReport locked(String reason, RecoveryReport.Unlock unlock) {
        return new RecoveryReport(true, reason, unlock, null, null, null, null, null, null);
    }

    static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
