package com.comedown.experience.devseed.application;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.DemoAccountSetup;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.cbi.contract.CbiCommands;
import com.comedown.cbi.contract.CbiQuery;
import com.comedown.cbi.contract.CbiResult;
import com.comedown.cbi.contract.SubmitCbi;
import com.comedown.checkin.contract.CheckinQuery;
import com.comedown.checkin.contract.ConditionCommands;
import com.comedown.checkin.contract.MeasurementCommands;
import com.comedown.checkin.contract.RecordCameraMeasurement;
import com.comedown.checkin.contract.SaveCondition;
import com.comedown.experience.devseed.application.DemoScenario.DemoDay;
import com.comedown.experience.morning.application.LowRecoverySafetyCheck;
import com.comedown.recovery.contract.DailyRecoverySnapshot;
import com.comedown.recovery.contract.RecoveryCommands;
import com.comedown.recovery.contract.RecoveryQuery;
import com.comedown.safety.contract.SafetyCommands;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * local 전용 시연 데이터 입력 흐름. 로그인한 사용자에게 시나리오의 4주 기록을 한 트랜잭션으로 넣는다.
 * 순서: 계정 준비(account) → 초기 CBI(cbi) + CBI_HIGH 평가(safety) → 날마다 카메라 측정·컨디션(checkin)
 * → 1일차부터 회복 재계산(recovery) → 2주 연속 낮음 평가(safety).
 * 실제 API와 같은 계산 경로를 쓰므로 회복 상태·기준선은 시나리오가 아니라 recovery-v1이 정한다.
 * <p>
 * 넣지 않는 것: 캘린더 일정·주간 부하(schedule/calendar 쓰기 계약 대기), 제안(RECOVERY 추천 임계값 미확정, PRE_START는 일정축),
 * 28일차 CBI(시연에서 직접 응답). 이미 건강 기록이 있는 사용자에게는 넣지 않는다(지우지 않음).
 */
@Service
@Profile("local")
public class DemoSeedFlow {

    static final LocalTime ONBOARDING_TIME = LocalTime.of(7, 0);
    static final LocalTime MEASURE_TIME = LocalTime.of(7, 30);
    static final Duration CBI_BEFORE_ONBOARDING = Duration.ofMinutes(10);
    static final List<String> NOT_SEEDED = List.of("SCHEDULE");
    /** MySQL DATE 최댓값(기록 유무 확인용). */
    static final LocalDate LAST_DATE = LocalDate.of(9999, 12, 31);

    private static final Logger log = LoggerFactory.getLogger(DemoSeedFlow.class);

    private final AccountAccessQuery accountAccess;
    private final DemoAccountSetup demoAccount;
    private final CbiCommands cbiCommands;
    private final CbiQuery cbiQuery;
    private final CheckinQuery checkinQuery;
    private final MeasurementCommands measurementCommands;
    private final ConditionCommands conditionCommands;
    private final RecoveryCommands recoveryCommands;
    private final RecoveryQuery recoveryQuery;
    private final SafetyCommands safetyCommands;
    private final LowRecoverySafetyCheck lowRecoverySafety;
    private final ServiceTime serviceTime;

    public DemoSeedFlow(AccountAccessQuery accountAccess, DemoAccountSetup demoAccount, CbiCommands cbiCommands,
            CbiQuery cbiQuery, CheckinQuery checkinQuery, MeasurementCommands measurementCommands,
            ConditionCommands conditionCommands, RecoveryCommands recoveryCommands, RecoveryQuery recoveryQuery,
            SafetyCommands safetyCommands, LowRecoverySafetyCheck lowRecoverySafety, ServiceTime serviceTime) {
        this.accountAccess = accountAccess;
        this.demoAccount = demoAccount;
        this.cbiCommands = cbiCommands;
        this.cbiQuery = cbiQuery;
        this.checkinQuery = checkinQuery;
        this.measurementCommands = measurementCommands;
        this.conditionCommands = conditionCommands;
        this.recoveryCommands = recoveryCommands;
        this.recoveryQuery = recoveryQuery;
        this.safetyCommands = safetyCommands;
        this.lowRecoverySafety = lowRecoverySafety;
        this.serviceTime = serviceTime;
    }

    @Transactional
    public DemoSeedResult seed(long userId, DemoScenario scenario) {
        requireEmptyAccount(userId);
        LocalDate today = serviceTime.today();
        LocalDate onboardingDate = today.minusDays(scenario.todayDayIndex() - 1L);
        Instant onboardingCompletedAt = at(onboardingDate, ONBOARDING_TIME);

        demoAccount.prepareOnboarded(userId, onboardingCompletedAt);

        CbiResult initial = cbiCommands.submit(new SubmitCbi(userId, "INITIAL", scenario.initialCbiOptions(),
                onboardingCompletedAt.minus(CBI_BEFORE_ONBOARDING)));
        safetyCommands.evaluateCbi(userId, initial.id(), initial.highRisk());

        LocalDate lastSeeded = onboardingDate;
        for (DemoDay day : scenario.days()) {
            LocalDate date = onboardingDate.plusDays(day.dayIndex() - 1L);
            if (!date.isBefore(today)) {
                throw new IllegalStateException("시나리오는 오늘 이전 날짜만 넣습니다: " + day.dayIndex());
            }
            SyntheticPpgSignal signal = SyntheticPpgSignal.withRmssd(day.rmssdMs(), day.dayIndex());
            measurementCommands.recordCamera(new RecordCameraMeasurement(userId, onboardingDate,
                    at(date, MEASURE_TIME), true, "captureTime", signal.t, signal.r, signal.g));
            conditionCommands.saveCondition(new SaveCondition(userId, onboardingDate, date, day.score(),
                    day.alcohol(), day.sick(), day.caffeine()));
            lastSeeded = date;
        }

        recoveryCommands.recalculate(userId, onboardingDate, today);
        lowRecoverySafety.evaluate(userId);

        String latestStatus = recoveryQuery.findDay(userId, lastSeeded)
                .map(DailyRecoverySnapshot::recoveryStatus)
                .orElse(null);
        log.info("Local dev seed: userId={}, scenario={}, onboardingDate={}, days={}",
                userId, scenario, onboardingDate, scenario.days().size());
        return new DemoSeedResult(scenario.name(), onboardingDate, today, scenario.todayDayIndex(),
                scenario.days().size(), latestStatus, List.of(initial.round()), NOT_SEEDED);
    }

    /** 사용자 행이 있고 탈퇴 중이 아니며 아침 체크·CBI 기록이 없어야 한다(기존 기록을 덮거나 지우지 않음). */
    private void requireEmptyAccount(long userId) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE,
                        "사용자 행이 없어요. users에 사용자를 먼저 만들어 주세요."));
        if (!access.active()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "탈퇴를 처리하고 있어요.");
        }
        boolean hasRecords = !checkinQuery.findDaysThrough(userId, LAST_DATE).isEmpty()
                || !cbiQuery.findHistory(userId).isEmpty();
        if (hasRecords) {
            throw new ApiException(ErrorCode.INVALID_STATE,
                    "이미 건강 기록이 있어요. 기록이 없는 사용자로 시드해 주세요.");
        }
    }

    private Instant at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(ServiceTime.ZONE).toInstant();
    }
}
