package com.comedown.recovery.contract;

import java.math.BigDecimal;

/**
 * AI 도입 전 회복 해석의 규칙 기반 문구(recovery 소유 정책). 사실만 전달하고 진단·권고는 하지 않는다.
 * 아침 결과(B6)와 회복 리포트(C1)가 같은 문구를 쓴다.
 */
public final class RecoveryFallbackText {

    private RecoveryFallbackText() {
    }

    /**
     * @param recoveryStatus 기준선 확정 후 상태(NORMAL·LOW·INSUFFICIENT)
     * @param changePct      평소 대비 %(추세가 없으면 null)
     */
    public static String of(String recoveryStatus, BigDecimal changePct) {
        String status = switch (recoveryStatus) {
            case "LOW" -> "최근 7일 회복이 평소 범위보다 낮아요.";
            case "NORMAL" -> "최근 7일 회복이 평소 범위 안에 있어요.";
            default -> "최근 7일 측정이 적어 평소와 비교하기 어려워요.";
        };
        if (changePct == null) {
            return status;
        }
        if (changePct.signum() == 0) {
            return status + " 평소와 비슷해요.";
        }
        return status + " 평소보다 " + changePct.abs().stripTrailingZeros().toPlainString() + "% "
                + (changePct.signum() > 0 ? "높아요." : "낮아요.");
    }
}
