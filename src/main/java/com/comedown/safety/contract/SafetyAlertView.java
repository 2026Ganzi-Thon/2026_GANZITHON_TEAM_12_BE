package com.comedown.safety.contract;

import java.time.Instant;

/**
 * 열린(조건이 풀리지 않은) 안전 안내.
 *
 * @param triggerType  LOW_RECOVERY_2W·CBI_HIGH
 * @param acknowledged 사용자가 닫았는지
 */
public record SafetyAlertView(long id, String triggerType, Instant triggeredAt, boolean acknowledged) {
}
