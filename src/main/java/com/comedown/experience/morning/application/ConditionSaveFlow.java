package com.comedown.experience.morning.application;

import com.comedown.checkin.contract.ConditionCommands;
import com.comedown.checkin.contract.SaveCondition;
import com.comedown.recovery.contract.RecoveryCommands;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 컨디션 저장 흐름(아침 체크 ②): 한 트랜잭션에서 checkin 저장 → 오늘 회복 재계산 → 오늘 결과 조합.
 * 측정 없이도 저장할 수 있고(카메라 실패한 날), 다시 저장하면 값을 바꾸고 결과를 다시 계산한다.
 * <p>
 * 아직 하지 않는 일: 2축 판단(judgment)·RECOVERY 추천 생성(추천 임계값 확정 후), 2주 연속 낮음 안전 평가(safety 구현 후).
 */
@Service
public class ConditionSaveFlow {

    private final ConditionCommands conditionCommands;
    private final RecoveryCommands recoveryCommands;
    private final TodayResultAssembler assembler;
    private final ServiceTime serviceTime;

    public ConditionSaveFlow(ConditionCommands conditionCommands, RecoveryCommands recoveryCommands,
            TodayResultAssembler assembler, ServiceTime serviceTime) {
        this.conditionCommands = conditionCommands;
        this.recoveryCommands = recoveryCommands;
        this.assembler = assembler;
        this.serviceTime = serviceTime;
    }

    @Transactional
    public TodayResult save(long userId, LocalDate onboardingDate, int score, boolean alcohol, boolean sick,
            boolean caffeine) {
        LocalDate today = serviceTime.today();
        conditionCommands.saveCondition(new SaveCondition(userId, onboardingDate, today, score, alcohol, sick, caffeine));
        recoveryCommands.recalculate(userId, today, today);
        return assembler.assemble(userId, today);
    }
}
