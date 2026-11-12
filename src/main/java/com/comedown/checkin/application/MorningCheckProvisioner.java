package com.comedown.checkin.application;

import com.comedown.checkin.contract.DayIndex;
import com.comedown.checkin.domain.MorningCheck;
import com.comedown.checkin.infrastructure.persistence.MorningCheckRepository;
import com.comedown.shared.time.ServiceTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * 그날 아침 체크를 확보한다(없으면 생성). 측정·컨디션·호흡 중 첫 저장에서 만든다.
 * UK(user_id, check_date) 경합에도 실패하지 않도록 ON DUPLICATE KEY 삽입 후 잠금 읽기로 최신 행을 가져온다.
 * 호출하는 쪽의 트랜잭션 안에서 쓴다.
 */
@Component
class MorningCheckProvisioner {

    private final MorningCheckRepository morningChecks;
    private final ServiceTime serviceTime;

    MorningCheckProvisioner(MorningCheckRepository morningChecks, ServiceTime serviceTime) {
        this.morningChecks = morningChecks;
        this.serviceTime = serviceTime;
    }

    MorningCheck ensure(long userId, LocalDate onboardingDate, LocalDate checkDate) {
        morningChecks.insertIfAbsent(userId, checkDate, DayIndex.of(onboardingDate, checkDate),
                LocalDateTime.ofInstant(serviceTime.now(), ZoneOffset.UTC));
        return morningChecks.findForUpdate(userId, checkDate)
                .orElseThrow(() -> new IllegalStateException("아침 체크를 만들지 못했습니다"));
    }
}
