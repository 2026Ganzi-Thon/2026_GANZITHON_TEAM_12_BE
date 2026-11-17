package com.comedown.experience.morning.application;

import com.comedown.ai.contract.AiInterpretation;
import com.comedown.recovery.contract.RecoveryFallbackText;
import org.springframework.stereotype.Component;

/**
 * 오늘 결과의 회복 해석 한 줄을 AI 문구로 바꾼다. 결과를 저장·조합하는 트랜잭션이 끝난 뒤(Controller에서) 부른다:
 * 외부 호출을 DB 트랜잭션에 넣지 않기 위해서다. 회복 리포트(C1)와 같은 종류·기준 날짜·사실을 써서 같은 문구가 나온다.
 */
@Component
public class TodayInterpretation {

    private final AiInterpretation ai;

    public TodayInterpretation(AiInterpretation ai) {
        this.ai = ai;
    }

    /** 해석이 없는 결과(기준선 확정 전)나 null은 그대로 돌려준다. */
    public TodayResult apply(long userId, TodayResult result) {
        if (result == null || result.interpretation() == null) {
            return result;
        }
        TodayResult.Recovery recovery = result.recovery();
        AiInterpretation.Text text = ai.interpret(new AiInterpretation.Request(userId,
                AiInterpretation.RECOVERY_INTERPRETATION, result.date(),
                RecoveryFallbackText.facts(recovery.status(), recovery.changePct()),
                result.interpretation().text()));
        return new TodayResult(result.date(), result.phase(), result.recovery(), result.qualityLevel(),
                result.rmssdMs(), result.heartRateBpm(), result.excludedFromBaseline(), result.baseline(),
                result.conditionScore(), result.judgment(),
                new TodayResult.Interpretation(text.text(), text.aiGenerated(), text.fallback()),
                result.suggestions());
    }

    public MorningCheckToday apply(long userId, MorningCheckToday today) {
        if (today.result() == null || today.result().interpretation() == null) {
            return today;
        }
        return new MorningCheckToday(today.date(), today.dayIndex(), today.phase(), today.steps(), today.baseline(),
                apply(userId, today.result()));
    }
}
