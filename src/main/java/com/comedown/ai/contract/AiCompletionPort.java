package com.comedown.ai.contract;

/**
 * AI 문장 생성 포트(전송은 integration.openai가 구현). 프롬프트와 결과 검증·대체 문구 같은 제품 규칙은 ai가 갖고,
 * 어댑터는 전송·타임아웃·응답 형식만 다룬다.
 * 실패는 {@link com.comedown.shared.error.ExternalServiceException}으로 알린다.
 */
public interface AiCompletionPort {

    /** API 키가 설정돼 호출할 수 있는지. false면 호출하지 않고 대체 문구를 쓴다. */
    boolean available();

    /** 사용하는 모델 이름(기록용). */
    String model();

    /**
     * @param system 규칙(역할·금지 사항)
     * @param user   요청과 사실
     * @return 모델이 쓴 문장(가공 전)
     */
    String complete(String system, String user);
}
