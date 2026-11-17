package com.comedown.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InterpretationPromptTest {

    private static final List<String> FACTS = List.of("최근 7일 회복 상태: 평소 범위보다 낮음", "평소 대비 변화: -6%");

    @Test
    void 요청에는_사실과_대체_문구만_들어간다() {
        String user = InterpretationPrompt.user("RECOVERY_INTERPRETATION", FACTS, "평소보다 6% 낮아요.");

        assertThat(user).contains("- 최근 7일 회복 상태: 평소 범위보다 낮음").contains("- 평소 대비 변화: -6%")
                .contains("평소보다 6% 낮아요.").contains("최근 회복 상태");
        assertThat(InterpretationPrompt.user("FOUR_WEEK_SUMMARY", FACTS, "x")).contains("4주 동안의 변화");
        assertThat(InterpretationPrompt.SYSTEM).contains("사실").contains("진단").contains("해요체");
    }

    @Test
    void 캐시_키는_종류_사실_대체_문구가_같으면_같고_하나라도_다르면_다르다() {
        String hash = InterpretationPrompt.inputHash("RECOVERY_INTERPRETATION", FACTS, "a");

        assertThat(hash).hasSize(64).isEqualTo(InterpretationPrompt.inputHash("RECOVERY_INTERPRETATION", FACTS, "a"));
        assertThat(hash).isNotEqualTo(InterpretationPrompt.inputHash("FOUR_WEEK_SUMMARY", FACTS, "a"));
        assertThat(hash).isNotEqualTo(InterpretationPrompt.inputHash("RECOVERY_INTERPRETATION", FACTS, "b"));
        assertThat(hash).isNotEqualTo(
                InterpretationPrompt.inputHash("RECOVERY_INTERPRETATION", List.of("평소 대비 변화: -7%"), "a"));
    }

    @Test
    void 결과는_공백과_감싼_따옴표를_다듬는다() {
        assertThat(InterpretationPrompt.clean("  \"최근 회복이\n평소보다  낮아요.\" ")).contains("최근 회복이 평소보다 낮아요.");
        assertThat(InterpretationPrompt.clean("“평소와 비슷해요.”")).contains("평소와 비슷해요.");
    }

    @Test
    void 비었거나_너무_길거나_의료_표현이_있으면_버린다() {
        assertThat(InterpretationPrompt.clean(null)).isEmpty();
        assertThat(InterpretationPrompt.clean("  \n ")).isEmpty();
        assertThat(InterpretationPrompt.clean("\"\"")).isEmpty();
        assertThat(InterpretationPrompt.clean("가".repeat(201))).isEmpty();
        assertThat(InterpretationPrompt.clean("가".repeat(200))).isPresent();
        assertThat(InterpretationPrompt.clean("회복이 낮아요. 병원에 가 보세요.")).isEmpty();
        assertThat(InterpretationPrompt.clean("번아웃으로 진단될 수 있어요.")).isEmpty();
        assertThat(InterpretationPrompt.clean("치료가 필요해 보여요.")).isEmpty();
    }
}
