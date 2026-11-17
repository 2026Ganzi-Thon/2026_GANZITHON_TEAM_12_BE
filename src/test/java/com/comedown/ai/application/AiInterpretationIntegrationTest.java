package com.comedown.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.comedown.ai.contract.AiCompletionPort;
import com.comedown.ai.contract.AiDataDeletion;
import com.comedown.ai.contract.AiInterpretation;
import com.comedown.ai.contract.AiInterpretation.Request;
import com.comedown.ai.contract.AiInterpretation.Text;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 해석 문구 생성·캐시·대체(ai_outputs). AI 전송은 대체 응답이다. */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class AiInterpretationIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);
    private static final List<String> FACTS = List.of("최근 7일 회복 상태: 평소 범위보다 낮음", "평소 대비 변화: -6%");
    private static final String FALLBACK = "최근 7일 회복이 평소 범위보다 낮아요. 평소보다 6% 낮아요.";

    @Autowired
    AiInterpretation ai;

    @Autowired
    AiDataDeletion deletion;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @MockitoBean
    AiCompletionPort port;

    private long userId;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        reset(port);
        when(port.available()).thenReturn(true);
        when(port.model()).thenReturn("test-model");
        userId = newUser();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'ai-%'");
    }

    @Test
    void AI가_쓴_문구를_저장하고_같은_입력이면_다시_부르지_않는다() {
        when(port.complete(any(), any())).thenReturn(" \"최근 회복이 평소보다 6% 낮아요.\" ");

        Text first = ai.interpret(request(FACTS));
        Text second = ai.interpret(request(FACTS));

        assertThat(first).isEqualTo(new Text("최근 회복이 평소보다 6% 낮아요.", true, false));
        assertThat(second).isEqualTo(first);
        verify(port, times(1)).complete(contains("사실"), contains("- 평소 대비 변화: -6%"));
        assertThat(row()).containsEntry("kind", "RECOVERY_INTERPRETATION")
                .containsEntry("content", "최근 회복이 평소보다 6% 낮아요.")
                .containsEntry("model", "test-model").containsEntry("is_fallback", false);
        assertThat((String) row().get("input_hash")).hasSize(64);
    }

    @Test
    void 사실이_바뀌거나_날짜가_바뀌면_새로_만든다() {
        when(port.complete(any(), any())).thenReturn("첫 문구예요.", "둘째 문구예요.", "셋째 문구예요.");

        assertThat(ai.interpret(request(FACTS)).text()).isEqualTo("첫 문구예요.");
        assertThat(ai.interpret(request(List.of("평소 대비 변화: -7%"))).text()).isEqualTo("둘째 문구예요.");
        assertThat(ai.interpret(new Request(userId, AiInterpretation.RECOVERY_INTERPRETATION, DAY.plusDays(1), FACTS,
                FALLBACK)).text()).isEqualTo("셋째 문구예요.");
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void 호출이_실패하면_대체_문구를_주고_10분_뒤에_다시_시도한다() {
        when(port.complete(any(), any())).thenThrow(new ExternalServiceException(Kind.UNCERTAIN, "timeout"));

        assertThat(ai.interpret(request(FACTS))).isEqualTo(new Text(FALLBACK, false, true));
        assertThat(row()).containsEntry("is_fallback", true).containsEntry("model", null)
                .containsEntry("content", FALLBACK);

        // 바로 다시 불러도 타임아웃을 또 기다리지 않는다.
        assertThat(ai.interpret(request(FACTS))).isEqualTo(new Text(FALLBACK, false, true));
        verify(port, times(1)).complete(any(), any());

        clock.advance(Duration.ofMinutes(11));
        reset(port);
        when(port.available()).thenReturn(true);
        when(port.model()).thenReturn("test-model");
        when(port.complete(any(), any())).thenReturn("이제는 AI 문구예요.");

        assertThat(ai.interpret(request(FACTS))).isEqualTo(new Text("이제는 AI 문구예요.", true, false));
        assertThat(count()).isEqualTo(1);
        assertThat(row()).containsEntry("is_fallback", false).containsEntry("model", "test-model");
    }

    @Test
    void 검증을_통과하지_못한_문구는_쓰지_않는다() {
        when(port.complete(any(), any())).thenReturn("회복이 낮아요. 병원에서 진단을 받아 보세요.");

        assertThat(ai.interpret(request(FACTS))).isEqualTo(new Text(FALLBACK, false, true));
        assertThat(row()).containsEntry("is_fallback", true).containsEntry("content", FALLBACK);
    }

    @Test
    void 키가_없으면_부르지도_저장하지도_않는다() {
        when(port.available()).thenReturn(false);

        assertThat(ai.interpret(request(FACTS))).isEqualTo(new Text(FALLBACK, false, true));
        verify(port, never()).complete(any(), any());
        assertThat(count()).isZero();
    }

    @Test
    void 사용자_행이_없어_저장하지_못해도_대체_문구를_준다() {
        when(port.complete(any(), any())).thenReturn("문구예요.");

        Text text = ai.interpret(new Request(-1L, AiInterpretation.RECOVERY_INTERPRETATION, DAY, FACTS, FALLBACK));

        assertThat(text).isEqualTo(new Text(FALLBACK, false, true));
    }

    @Test
    void 저장하지_않는_쓰기는_성공한_문구만_돌려주고_ai_outputs에_남기지_않는다() {
        when(port.complete(contains("가볍게 권한다"), contains("추천 카드"))).thenReturn("20분 걷기 어때요?");

        assertThat(ai.write(AiInterpretation.SUGGESTION_MESSAGE, FACTS, FALLBACK)).contains("20분 걷기 어때요?");
        assertThat(count()).isZero();

        when(port.complete(any(), any())).thenThrow(new ExternalServiceException(Kind.UNCERTAIN, "timeout"));
        assertThat(ai.write(AiInterpretation.SUGGESTION_MESSAGE, FACTS, FALLBACK)).isEmpty();
        when(port.available()).thenReturn(false);
        assertThat(ai.write(AiInterpretation.SUGGESTION_MESSAGE, FACTS, FALLBACK)).isEmpty();
    }

    @Test
    void 삭제는_그_사용자의_문구만_지운다() {
        when(port.complete(any(), any())).thenReturn("문구예요.");
        long other = newUser();
        ai.interpret(request(FACTS));
        ai.interpret(new Request(other, AiInterpretation.FOUR_WEEK_SUMMARY, DAY, FACTS, FALLBACK));

        assertThat(deletion.deleteAll(userId)).isEqualTo(1);

        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_outputs WHERE user_id = ?", Integer.class, other))
                .isEqualTo(1);
    }

    private Request request(List<String> facts) {
        return new Request(userId, AiInterpretation.RECOVERY_INTERPRETATION, DAY, facts, FALLBACK);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT kind, input_hash, content, model, is_fallback FROM ai_outputs "
                + "WHERE user_id = ?", userId);
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_outputs WHERE user_id = ?", Integer.class, userId);
    }

    private long newUser() {
        String sub = "ai-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, created_at, last_login_at) "
                + "VALUES (?, 'DONE', '2026-09-01 00:00:00', '2026-09-01 00:00:00')", sub);
        return jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
    }
}
