package com.comedown.experience.morning.application;

import com.comedown.ai.contract.AiInterpretation;
import com.comedown.experience.suggestions.application.SuggestionMessageAi;
import com.comedown.recommendation.contract.SuggestionPayload;
import com.comedown.recovery.contract.RecoveryFallbackText;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/**
 * 오늘 결과의 AI 문구: 회복 해석 한 줄과 추천 카드 문구. 결과를 저장·조합하는 트랜잭션이 끝난 뒤(Controller에서) 부른다:
 * 외부 호출을 DB 트랜잭션에 넣지 않기 위해서다. 두 문구는 동시에 요청해 결과 화면이 늦어지지 않게 한다.
 * 회복 해석은 회복 리포트(C1)와 같은 종류·기준 날짜·사실을 써서 같은 문구가 나온다.
 */
@Component
public class TodayInterpretation {

    private final AiInterpretation ai;
    private final SuggestionMessageAi suggestionMessages;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TodayInterpretation(AiInterpretation ai, SuggestionMessageAi suggestionMessages) {
        this.ai = ai;
        this.suggestionMessages = suggestionMessages;
    }

    /** null은 그대로 돌려준다. 해석이 없는 결과(기준선 확정 전)는 추천 문구만 본다. */
    public TodayResult apply(long userId, TodayResult result) {
        if (result == null) {
            return null;
        }
        CompletableFuture<List<SuggestionPayload>> suggestions = CompletableFuture.supplyAsync(
                () -> suggestionMessages.apply(userId, result.suggestions()), executor);
        TodayResult.Interpretation interpretation = interpretation(userId, result);
        return new TodayResult(result.date(), result.phase(), result.recovery(), result.qualityLevel(),
                result.rmssdMs(), result.heartRateBpm(), result.excludedFromBaseline(), result.baseline(),
                result.conditionScore(), result.judgment(), interpretation, suggestions.join());
    }

    private TodayResult.Interpretation interpretation(long userId, TodayResult result) {
        if (result.interpretation() == null) {
            return null;
        }
        TodayResult.Recovery recovery = result.recovery();
        AiInterpretation.Text text = ai.interpret(new AiInterpretation.Request(userId,
                AiInterpretation.RECOVERY_INTERPRETATION, result.date(),
                RecoveryFallbackText.facts(recovery.status(), recovery.changePct()),
                result.interpretation().text()));
        return new TodayResult.Interpretation(text.text(), text.aiGenerated(), text.fallback());
    }

    public MorningCheckToday apply(long userId, MorningCheckToday today) {
        if (today.result() == null) {
            return today;
        }
        return new MorningCheckToday(today.date(), today.dayIndex(), today.phase(), today.steps(), today.baseline(),
                apply(userId, today.result()));
    }
}
