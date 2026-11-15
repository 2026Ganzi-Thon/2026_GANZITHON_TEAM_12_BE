package com.comedown.experience.assessments.api;

import com.comedown.cbi.domain.CbiQuestionnaire;
import com.comedown.shared.error.ErrorResponse.FieldError;
import com.comedown.shared.error.FieldValidationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * CBI 제출 형식 검증(400): 회차 INITIAL·DAY28, 문항 버전 일치, 서로 다른 번호 1~13 정확히 13개, 보기 0~4.
 */
@Component
public class CbiSubmitValidator {

    static final Set<String> ROUNDS = Set.of("INITIAL", "DAY28");

    /** @return 문항 번호 → 원래 선택 */
    public Map<Integer, Integer> validate(CbiSubmitRequest request) {
        List<FieldError> errors = new ArrayList<>();
        if (request.round() == null || !ROUNDS.contains(request.round())) {
            errors.add(new FieldError("round", "INITIAL 또는 DAY28이어야 해요."));
        }
        if (!CbiQuestionnaire.VERSION.equals(request.questionnaireVersion())) {
            errors.add(new FieldError("questionnaireVersion", "문항 버전이 달라요. 문항을 다시 불러와 주세요."));
        }
        Map<Integer, Integer> options = new LinkedHashMap<>();
        List<CbiSubmitRequest.Answer> answers = request.answers();
        if (answers == null || answers.size() != CbiQuestionnaire.ITEM_COUNT) {
            errors.add(new FieldError("answers", "13개 문항에 모두 답해야 해요."));
        } else {
            Set<Integer> seen = new HashSet<>();
            for (CbiSubmitRequest.Answer answer : answers) {
                if (answer == null || answer.no() == null || answer.no() < 1
                        || answer.no() > CbiQuestionnaire.ITEM_COUNT) {
                    errors.add(new FieldError("answers", "문항 번호는 1~13이어야 해요."));
                    break;
                }
                if (!seen.add(answer.no())) {
                    errors.add(new FieldError("answers", "같은 문항에 두 번 답했어요: " + answer.no()));
                    break;
                }
                if (answer.option() == null || answer.option() < 0 || answer.option() > 4) {
                    errors.add(new FieldError("answers", "보기는 0~4여야 해요."));
                    break;
                }
                options.put(answer.no(), answer.option());
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        return options;
    }
}
