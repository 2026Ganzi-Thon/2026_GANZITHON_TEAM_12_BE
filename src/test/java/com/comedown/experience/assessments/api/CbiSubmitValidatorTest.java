package com.comedown.experience.assessments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.comedown.shared.error.ErrorResponse.FieldError;
import com.comedown.shared.error.FieldValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CbiSubmitValidatorTest {

    private static final String VERSION = "cbi-ko-2026.11.2";
    private final CbiSubmitValidator validator = new CbiSubmitValidator();

    private static List<CbiSubmitRequest.Answer> answers() {
        return new ArrayList<>(IntStream.rangeClosed(1, 13)
                .mapToObj(no -> new CbiSubmitRequest.Answer(no, no % 5))
                .toList());
    }

    private static List<String> fields(CbiSubmitRequest request, CbiSubmitValidator validator) {
        try {
            validator.validate(request);
            return List.of();
        } catch (FieldValidationException e) {
            return e.fieldErrors().stream().map(FieldError::field).toList();
        }
    }

    @Test
    void 올바른_요청은_문항별_선택을_돌려준다() {
        assertThat(validator.validate(new CbiSubmitRequest("INITIAL", VERSION, answers())))
                .hasSize(13).containsEntry(1, 1).containsEntry(13, 3);
    }

    @Test
    void 회차는_INITIAL과_DAY28만() {
        assertThat(fields(new CbiSubmitRequest("FOLLOW_UP", VERSION, answers()), validator)).containsExactly("round");
        assertThat(fields(new CbiSubmitRequest(null, VERSION, answers()), validator)).containsExactly("round");
    }

    @Test
    void 문항_버전이_다르면_400() {
        assertThat(fields(new CbiSubmitRequest("INITIAL", "cbi-ko-2026.11.1", answers()), validator))
                .containsExactly("questionnaireVersion");
    }

    @Test
    void 답은_서로_다른_1에서_13번_정확히_13개이고_보기는_0에서_4() {
        List<CbiSubmitRequest.Answer> twelve = answers();
        twelve.removeLast();
        assertThat(fields(new CbiSubmitRequest("INITIAL", VERSION, twelve), validator)).containsExactly("answers");

        List<CbiSubmitRequest.Answer> duplicated = answers();
        duplicated.set(12, new CbiSubmitRequest.Answer(1, 0));
        assertThat(fields(new CbiSubmitRequest("INITIAL", VERSION, duplicated), validator)).containsExactly("answers");

        List<CbiSubmitRequest.Answer> outOfRange = answers();
        outOfRange.set(12, new CbiSubmitRequest.Answer(14, 0));
        assertThat(fields(new CbiSubmitRequest("INITIAL", VERSION, outOfRange), validator)).containsExactly("answers");

        List<CbiSubmitRequest.Answer> badOption = answers();
        badOption.set(0, new CbiSubmitRequest.Answer(1, 5));
        assertThat(fields(new CbiSubmitRequest("INITIAL", VERSION, badOption), validator)).containsExactly("answers");

        assertThatThrownBy(() -> validator.validate(new CbiSubmitRequest("INITIAL", VERSION, null)))
                .isInstanceOf(FieldValidationException.class);
    }
}
