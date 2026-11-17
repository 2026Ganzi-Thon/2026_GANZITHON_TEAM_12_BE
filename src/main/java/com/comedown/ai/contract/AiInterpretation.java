package com.comedown.ai.contract;

import java.time.LocalDate;
import java.util.List;

/**
 * 해석·요약 문구를 AI로 쓰는 계약. 같은 입력이면 저장된 문구를 돌려주고(ai_outputs), AI를 쓸 수 없거나 실패하면
 * 호출하는 쪽이 준 규칙 기반 문구로 대체한다. 외부 호출이 있으므로 DB 트랜잭션 밖에서 부른다.
 * <p>
 * 사실에는 수치와 상태만 넣는다. 이름·이메일·일정 제목 같은 식별 정보는 넣지 않는다.
 */
public interface AiInterpretation {

    /** 회복 해석(아침 결과 B6·회복 리포트 C1 공용). */
    String RECOVERY_INTERPRETATION = "RECOVERY_INTERPRETATION";
    /** 28일차 비교 리포트 요약. */
    String FOUR_WEEK_SUMMARY = "FOUR_WEEK_SUMMARY";

    /**
     * @param kind         문구 종류(ai_outputs.kind)
     * @param refDate      기준 날짜(같은 날 같은 사실이면 다시 만들지 않는다)
     * @param facts        문구에 쓸 사실(한 줄에 하나, 수치·상태만)
     * @param fallbackText AI를 쓰지 못할 때의 규칙 기반 문구
     */
    record Request(long userId, String kind, LocalDate refDate, List<String> facts, String fallbackText) {

        public Request {
            facts = List.copyOf(facts);
        }
    }

    /** API 공통 AI 문구 모양: aiGenerated면 "AI 생성" 표시, fallback이면 규칙 기반 문구. */
    record Text(String text, boolean aiGenerated, boolean fallback) {
    }

    Text interpret(Request request);
}
