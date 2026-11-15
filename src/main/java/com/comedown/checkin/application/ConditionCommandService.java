package com.comedown.checkin.application;

import com.comedown.checkin.contract.ConditionCommands;
import com.comedown.checkin.contract.SaveCondition;
import com.comedown.checkin.domain.MorningCheck;
import com.comedown.shared.time.ServiceTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 컨디션 저장 유스케이스. */
@Service
public class ConditionCommandService implements ConditionCommands {

    private final MorningCheckProvisioner provisioner;
    private final ServiceTime serviceTime;

    ConditionCommandService(MorningCheckProvisioner provisioner, ServiceTime serviceTime) {
        this.provisioner = provisioner;
        this.serviceTime = serviceTime;
    }

    @Override
    @Transactional
    public long saveCondition(SaveCondition command) {
        MorningCheck check = provisioner.ensure(command.userId(), command.onboardingDate(), command.checkDate());
        check.saveCondition(command.score(), command.alcohol(), command.sick(), command.caffeine(),
                serviceTime.now());
        return check.getId();
    }
}
