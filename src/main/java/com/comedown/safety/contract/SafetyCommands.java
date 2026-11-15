package com.comedown.safety.contract;

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
}
