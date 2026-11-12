package com.comedown.experience.assessments.api;

import java.util.List;

/**
 * {@code POST /api/cbi/assessments} 요청.
 *
 * @param round                INITIAL·DAY28(FOLLOW_UP 미지원)
 * @param questionnaireVersion 문항 조회의 version
 * @param answers              서로 다른 번호 1~13의 응답 정확히 13개
 */
public record CbiSubmitRequest(String round, String questionnaireVersion, List<Answer> answers) {

    /** @param option 보기 순서(0~4) */
    public record Answer(Integer no, Integer option) {
    }
}
