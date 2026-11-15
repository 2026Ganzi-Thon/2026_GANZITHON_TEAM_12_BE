package com.comedown.safety.contract;

import java.time.LocalDate;
import java.util.List;

/**
 * safety가 공개하는 안전 평가 계약. cbi·recovery는 safety를 호출하지 않고, experience가 결과를 넘겨 평가를 요청한다.
 */
public interface SafetyCommands {

    /**
     * CBI 결과로 CBI_HIGH 안내를 평가한다. 호출하는 쪽의 트랜잭션에 참여한다.
     * <ul>
     *   <li>highRisk면 그 응답을 근거로 새 안내를 만든다(이미 열린 CBI_HIGH 안내는 해제하고 새 응답으로 대체).</li>
     *   <li>highRisk가 아니면 열린 CBI_HIGH 안내를 해제한다(가장 최근 CBI 기준).</li>
     * </ul>
     *
     * @return 이 평가 뒤 CBI_HIGH 안내가 열려 있는지
     */
    boolean evaluateCbi(long userId, long assessmentId, boolean highRisk);

    /**
     * 최근 회복 상태로 LOW_RECOVERY_2W 안내를 평가한다(계산 정책 5절). 호출하는 쪽의 트랜잭션에 참여한다.
     * <ul>
     *   <li>조건 충족 + 열린 안내 없음 → 새 안내(근거: 낮음 구간 첫 날짜)</li>
     *   <li>조건 충족 + 열린 안내 있음 → 그대로(같은 연속 구간에서는 다시 띄우지 않음, 닫았어도 유지)</li>
     *   <li>조건 미충족 → 열린 안내 해제(다시 충족되면 새 안내)</li>
     * </ul>
     *
     * @param days 최근 14일(today 포함)의 하루 회복 상태. 기록 없는 날은 빠져도 된다
     */
    void evaluateLowRecovery(long userId, LocalDate today, List<RecoveryDayStatus> days);

    /**
     * 사용자가 안내를 닫는다. 조건 해제와 다르다. 다시 불러도 처음 시각을 유지한다.
     *
     * @throws com.comedown.shared.error.ApiException 본인 안내가 아니면 404 NOT_FOUND
     */
    void acknowledge(long userId, long alertId);
}
