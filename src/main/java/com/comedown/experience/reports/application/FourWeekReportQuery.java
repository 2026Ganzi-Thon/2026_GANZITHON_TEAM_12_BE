package com.comedown.experience.reports.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.cbi.contract.CbiQuery;
import com.comedown.cbi.contract.CbiResult;
import com.comedown.checkin.contract.DayIndex;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.recovery.contract.RecoveryQuery;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.springframework.stereotype.Service;

/**
 * 28일차 비교 리포트 조합. cbi·recovery·recommendation 읽기 계약의 스냅샷만 쓴다.
 * <p>
 * 아직 비어 있는 값: load(schedule 계약), plan(subscription 계약), AI 해석(규칙 기반 문구로 대체).
 */
@Service
public class FourWeekReportQuery {

    static final int DAY28 = 28;
    static final int WEEK = 7;
    static final String CONSENT_REQUIRED = "CONSENT_REQUIRED";

    private final AccountAccessQuery accountAccess;
    private final CbiQuery cbiQuery;
    private final RecoveryQuery recoveryQuery;
    private final SuggestionQuery suggestionQuery;
    private final ServiceTime serviceTime;

    public FourWeekReportQuery(AccountAccessQuery accountAccess, CbiQuery cbiQuery, RecoveryQuery recoveryQuery,
            SuggestionQuery suggestionQuery, ServiceTime serviceTime) {
        this.accountAccess = accountAccess;
        this.cbiQuery = cbiQuery;
        this.recoveryQuery = recoveryQuery;
        this.suggestionQuery = suggestionQuery;
        this.serviceTime = serviceTime;
    }

    /** 온보딩 완료 후에만(403). */
    public FourWeekReport report(long userId) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!access.onboardingCompleted()) {
            throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
        }
        LocalDate day1 = serviceTime.toServiceDate(access.onboardingCompletedAt());
        LocalDate day28 = day1.plusDays(DAY28 - 1L);
        LocalDate today = serviceTime.today();

        if (!access.sensitiveHrvConsented()) {
            return unavailable(false, day28, CONSENT_REQUIRED);
        }
        if (DayIndex.of(day1, today) < DAY28) {
            return unavailable(false, day28, null);
        }
        Optional<CbiResult> day28Cbi = cbiQuery.findRound(userId, "DAY28");
        if (day28Cbi.isEmpty()) {
            return unavailable(true, day28, null);
        }
        Optional<CbiResult> initialCbi = cbiQuery.findRound(userId, "INITIAL");
        FourWeekReport.Cbi cbi = cbi(initialCbi, day28Cbi.get());
        FourWeekReport.Recovery recovery = recovery(userId, day1, day28);
        FourWeekReport.Suggestions suggestions = suggestions(userId, day1, day28);
        return new FourWeekReport(true, false, day28, null, cbi, recovery, null, suggestions,
                interpretation(cbi, recovery), null);
    }

    private static FourWeekReport unavailable(boolean cbiDue, LocalDate availableFrom, String reason) {
        return new FourWeekReport(false, cbiDue, availableFrom, reason, null, null, null, null, null, null);
    }

    private static FourWeekReport.Cbi cbi(Optional<CbiResult> initial, CbiResult day28) {
        FourWeekReport.Scores last = scores(day28.personalScore(), day28.workScore());
        if (initial.isEmpty()) {
            return new FourWeekReport.Cbi(null, last, null);
        }
        CbiResult first = initial.get();
        return new FourWeekReport.Cbi(
                scores(first.personalScore(), first.workScore()),
                last,
                scores(day28.personalScore().subtract(first.personalScore()),
                        day28.workScore().subtract(first.workScore())));
    }

    private FourWeekReport.Recovery recovery(long userId, LocalDate day1, LocalDate day28) {
        List<DailyRecoverySnapshot> states = recoveryQuery.findRange(userId, day1, day28);
        OptionalDouble first = eligibleLnMean(states, day1, day1.plusDays(WEEK - 1L));
        OptionalDouble last = eligibleLnMean(states, day28.minusDays(WEEK - 1L), day28);
        BigDecimal change = first.isPresent() && last.isPresent()
                ? BigDecimal.valueOf((Math.exp(last.getAsDouble() - first.getAsDouble()) - 1) * 100)
                        .setScale(1, RoundingMode.HALF_UP)
                : null;
        return new FourWeekReport.Recovery(
                first.isPresent() ? round1(Math.exp(first.getAsDouble())) : null,
                last.isPresent() ? round1(Math.exp(last.getAsDouble())) : null,
                change);
    }

    /** 기간 안 대표값이 있고 교란일이 아닌 날의 ln 평균(단순 RMSSD 산술 평균은 쓰지 않음). */
    private static OptionalDouble eligibleLnMean(List<DailyRecoverySnapshot> states, LocalDate from, LocalDate to) {
        return states.stream()
                .filter(s -> !s.date().isBefore(from) && !s.date().isAfter(to))
                .filter(s -> s.dailyLnRmssd() != null && !s.confounded())
                .mapToDouble(DailyRecoverySnapshot::dailyLnRmssd)
                .average();
    }

    private FourWeekReport.Suggestions suggestions(long userId, LocalDate day1, LocalDate day28) {
        List<SuggestionView> inPeriod = suggestionQuery.search(userId, null, null, null).stream()
                .filter(s -> !s.targetDate().isBefore(day1) && !s.targetDate().isAfter(day28))
                .toList();
        return new FourWeekReport.Suggestions(
                inPeriod.stream().filter(s -> "APPROVED".equals(s.status())).count(),
                inPeriod.stream().filter(s -> s.doneAt() != null).count());
    }

    /** AI 도입 전 규칙 기반 문구(사실만 전달, 진단·권고 없음). */
    static RecoveryReport.Interpretation interpretation(FourWeekReport.Cbi cbi, FourWeekReport.Recovery recovery) {
        StringBuilder text = new StringBuilder();
        if (cbi.diff() != null) {
            text.append(change("몸과 마음의 피로", cbi.diff().personal(), "점"))
                    .append(' ')
                    .append(change("일·공부로 인한 피로", cbi.diff().work(), "점"));
        }
        if (recovery.changePct() != null) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(change("첫 주보다 마지막 주 회복", recovery.changePct(), "%"));
        }
        if (text.isEmpty()) {
            text.append("4주 동안의 기록이 모였어요.");
        }
        return new RecoveryReport.Interpretation(text.toString(), false, true);
    }

    private static String change(String subject, BigDecimal delta, String unit) {
        boolean batchim = hasBatchim(subject);
        if (delta.signum() == 0) {
            return subject + (batchim ? "은" : "는") + " 비슷해요.";
        }
        String amount = delta.abs().stripTrailingZeros().toPlainString() + unit;
        return subject + (batchim ? "이 " : "가 ") + amount + (delta.signum() > 0 ? " 높아졌어요." : " 낮아졌어요.");
    }

    /** 마지막 글자에 받침이 있는지(조사 이/가, 은/는 선택). */
    static boolean hasBatchim(String word) {
        char last = word.charAt(word.length() - 1);
        return last >= 0xAC00 && last <= 0xD7A3 && (last - 0xAC00) % 28 != 0;
    }

    private static FourWeekReport.Scores scores(BigDecimal personal, BigDecimal work) {
        return new FourWeekReport.Scores(personal.setScale(1, RoundingMode.HALF_UP),
                work.setScale(1, RoundingMode.HALF_UP));
    }

    static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
