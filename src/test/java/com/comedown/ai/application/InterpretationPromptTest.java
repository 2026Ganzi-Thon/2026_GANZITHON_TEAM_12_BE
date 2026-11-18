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
    void 추천_카드는_제안만_권하는_규칙을_쓰고_해석은_권유를_금지한다() {
        assertThat(InterpretationPrompt.system("SUGGESTION_MESSAGE")).contains("'제안 내용'과 그 날짜·시각만 가볍게 권한다")
                .contains("'제안 날짜'·'제안 시각'에 적힌 표현을 그대로 쓴다")
                .contains("진단").doesNotContain("조언이나 권유, 몸 상태에 대한 평가를 덧붙이지 않는다");
        assertThat(InterpretationPrompt.system("RECOVERY_INTERPRETATION")).isEqualTo(InterpretationPrompt.SYSTEM)
                .contains("조언이나 권유, 몸 상태에 대한 평가를 덧붙이지 않는다");
        assertThat(InterpretationPrompt.user("SUGGESTION_MESSAGE", FACTS, "x")).contains("추천 카드");
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

    private static final List<String> LATER = List.of("제안 종류: 마감 전에 미리 시작하기", "제안 내용: 과제 미리 시작하기 (30분)",
            "제안 날짜: 11월 24일 화요일", "제안 시각: 오전 7시");
    private static final List<String> TODAY = List.of("제안 내용: 20분 걷기 (20분)", "제안 날짜: 오늘(11월 18일 수요일)",
            "제안 시각: 오후 12시 30분");

    @Test
    void 추천_카드는_사실에_없는_오늘_내일이나_다른_날짜_시각을_쓰면_버린다() {
        // 2026-11-18 운영에서 나온 문구: 11월 24일 제안을 "오늘"이라고 썼다.
        assertThat(InterpretationPrompt.clean("SUGGESTION_MESSAGE",
                "다가오는 마감이 많아요. 오늘 7시에 과제를 30분 미리 시작해 보는 건 어떨까요?", LATER)).isEmpty();
        assertThat(InterpretationPrompt.clean("SUGGESTION_MESSAGE", "내일 오전 7시에 30분만 시작해 볼까요?", LATER)).isEmpty();
        assertThat(InterpretationPrompt.clean("SUGGESTION_MESSAGE", "11월 25일 오전 7시에 시작해 볼까요?", LATER)).isEmpty();
        assertThat(InterpretationPrompt.clean("SUGGESTION_MESSAGE", "11월 24일 오전 9시에 시작해 볼까요?", LATER)).isEmpty();
        // 앞에 숫자가 붙은 "12시"를 사실의 "2시"로 보지 않는다.
        assertThat(InterpretationPrompt.clean("SUGGESTION_MESSAGE", "11월 24일 12시에 시작해 볼까요?",
                List.of("제안 날짜: 11월 24일 화요일", "제안 시각: 오후 2시"))).isEmpty();
    }

    @Test
    void 추천_카드는_사실의_날짜_시각_표현을_쓰면_통과한다() {
        assertThat(InterpretationPrompt.clean("SUGGESTION_MESSAGE",
                "다음 주에 마감이 몰려 있어요. 11월 24일 화요일 오전 7시에 과제를 30분만 미리 시작해 볼까요?", LATER)).isPresent();
        assertThat(InterpretationPrompt.clean("SUGGESTION_MESSAGE", "마감 전에 30분만 미리 시작해 볼까요?", LATER)).isPresent();
        assertThat(InterpretationPrompt.clean("SUGGESTION_MESSAGE", "오늘 12시 30분에 20분 걸어 볼까요?", TODAY)).isPresent();
        // "1시간"의 "시간"은 시각이 아니다.
        assertThat(InterpretationPrompt.clean("SUGGESTION_MESSAGE", "1시간도 안 걸려요. 30분만 시작해 볼까요?", LATER))
                .isPresent();
    }

    @Test
    void 날짜_검사는_추천_카드에만_한다() {
        assertThat(InterpretationPrompt.clean("RECOVERY_INTERPRETATION", "오늘 회복이 평소 범위보다 낮아요.", FACTS))
                .contains("오늘 회복이 평소 범위보다 낮아요.");
    }
}
