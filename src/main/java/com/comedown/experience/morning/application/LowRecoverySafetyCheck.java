package com.comedown.experience.morning.application;

import com.comedown.recovery.contract.RecoveryQuery;
import com.comedown.safety.contract.RecoveryDayStatus;
import com.comedown.safety.contract.SafetyCommands;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 회복 재계산 뒤 2주 연속 낮음(LOW_RECOVERY_2W) 안전 평가를 요청한다.
 * recovery는 safety를 호출하지 않으므로 experience가 최근 14일 회복 상태를 넘긴다.
 */
@Component
public class LowRecoverySafetyCheck {

    static final int WINDOW_DAYS = 14;

    private final RecoveryQuery recoveryQuery;
    private final SafetyCommands safetyCommands;
    private final ServiceTime serviceTime;

    public LowRecoverySafetyCheck(RecoveryQuery recoveryQuery, SafetyCommands safetyCommands,
            ServiceTime serviceTime) {
        this.recoveryQuery = recoveryQuery;
        this.safetyCommands = safetyCommands;
        this.serviceTime = serviceTime;
    }

    /** 호출하는 쪽의 트랜잭션 안에서 실행한다(재계산 결과를 읽기 위해). */
    public void evaluate(long userId) {
        LocalDate today = serviceTime.today();
        List<RecoveryDayStatus> days = recoveryQuery.findRange(userId, today.minusDays(WINDOW_DAYS - 1), today)
                .stream()
                .map(s -> new RecoveryDayStatus(s.date(), s.dailyLnRmssd() != null, s.recoveryStatus()))
                .toList();
        safetyCommands.evaluateLowRecovery(userId, today, days);
    }
}
