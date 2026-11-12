package com.comedown.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** API 명세 5절 제안 상태 규칙. 제안 시간: 2026-11-12 16:00~16:20 서울(07:00~07:20 UTC). */
class SuggestionTest {

    private static final Instant START = Instant.parse("2026-11-12T07:00:00Z");
    private static final Instant END = Instant.parse("2026-11-12T07:20:00Z");
    private static final Instant NOW = Instant.parse("2026-11-11T22:30:00Z");

    private static Suggestion proposed() {
        return Suggestion.propose(1, SuggestionType.RECOVERY, LocalDate.of(2026, 11, 12), START, END, "20분 걷기",
                "WALK", "OVERLOAD_LOW", "LOW_RECOVERY", "recovery-v1", "걸어 볼까요?", false, true, null, null,
                Instant.parse("2026-11-12T15:00:00Z"), "cdkey", NOW);
    }

    private static Suggestion approved() {
        Suggestion s = proposed();
        s.beginApproval(null, null, NOW);
        s.confirmApproved("cdkey", NOW);
        return s;
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void 건너뛰기는_PROPOSED에서만_되고_반복은_같은_결과() {
        Suggestion s = proposed();
        s.dismiss(NOW);
        s.dismiss(NOW);
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.DISMISSED);
        assertThat(s.getDecidedAt()).isEqualTo(NOW);

        assertCode(() -> approved().dismiss(NOW), ErrorCode.INVALID_STATE);
    }

    @Test
    void 승인은_PROPOSED에서_APPROVING을_거쳐_APPROVED() {
        Suggestion s = proposed();
        assertThat(s.beginApproval(null, null, NOW)).isTrue();
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.APPROVING);
        // 같은 승인 재시도는 바꾸지 않는다
        assertThat(s.beginApproval(null, null, NOW)).isFalse();

        s.confirmApproved("cdkey", NOW);
        s.confirmApproved("cdkey", NOW);
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.APPROVED);
        assertThat(s.getGoogleEventId()).isEqualTo("cdkey");
        assertThat(s.beginApproval(null, null, NOW)).isFalse();
    }

    @Test
    void 확정_실패는_FAILED이고_FAILED에서_다시_승인할_수_있다() {
        Suggestion s = proposed();
        s.beginApproval(null, null, NOW);
        s.markFailed(NOW);
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.FAILED);

        assertThat(s.beginApproval(null, null, NOW)).isTrue();
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.APPROVING);
        assertCode(() -> proposed().markFailed(NOW), ErrorCode.INVALID_STATE);
    }

    @Test
    void 건너뛴_제안은_승인할_수_없다() {
        Suggestion s = proposed();
        s.dismiss(NOW);

        assertCode(() -> s.beginApproval(null, null, NOW), ErrorCode.SUGGESTION_NOT_PROPOSED);
    }

    @Test
    void 승인_때_시간을_바꾸면_같은_길이여야_한다() {
        Suggestion s = proposed();
        assertThatThrownBy(() -> s.beginApproval(START.plusSeconds(3600), END.plusSeconds(3660), NOW))
                .isInstanceOf(IllegalArgumentException.class);

        s.beginApproval(START.plusSeconds(3600), END.plusSeconds(3600), NOW);
        assertThat(s.getStartAt()).isEqualTo(START.plusSeconds(3600));
    }

    @Test
    void 했어요는_APPROVED이고_일정이_끝난_뒤에만_상태는_유지() {
        Suggestion s = approved();
        assertCode(() -> s.markDone(END.minusSeconds(1)), ErrorCode.INVALID_STATE);

        s.markDone(END);
        s.markDone(END.plusSeconds(60));
        assertThat(s.getDoneAt()).isEqualTo(END);
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.APPROVED);
        assertCode(() -> proposed().markDone(END), ErrorCode.INVALID_STATE);
    }

    @Test
    void 되돌리기는_APPROVED이고_했어요_전에만_반복은_같은_결과() {
        Suggestion s = approved();
        s.confirmUndone(NOW);
        s.confirmUndone(NOW);
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.UNDONE);

        Suggestion done = approved();
        done.markDone(END);
        assertCode(done::requireUndoable, ErrorCode.INVALID_STATE);
        assertCode(() -> proposed().confirmUndone(NOW), ErrorCode.INVALID_STATE);
    }

    @Test
    void 만료는_PROPOSED이고_만료_시각이_지났을_때만() {
        Suggestion s = proposed();
        assertThat(s.expireIfDue(Instant.parse("2026-11-12T14:59:59Z"))).isFalse();
        assertThat(s.expireIfDue(Instant.parse("2026-11-12T15:00:00Z"))).isTrue();
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.EXPIRED);
        assertThat(approved().expireIfDue(Instant.parse("2026-11-13T00:00:00Z"))).isFalse();
    }
}
