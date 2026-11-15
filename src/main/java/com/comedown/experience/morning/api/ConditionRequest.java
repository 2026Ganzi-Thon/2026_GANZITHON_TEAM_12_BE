package com.comedown.experience.morning.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PUT /api/morning-checks/today/condition} 요청. 교란 요인 3개는 모두 보내야 한다(입력 전을 무교란으로 보지 않음).
 */
public record ConditionRequest(
        @NotNull(message = "필수예요.") @Min(value = 1, message = "1~5여야 해요.") @Max(value = 5, message = "1~5여야 해요.")
        Integer score,
        @NotNull(message = "필수예요.") Boolean alcohol,
        @NotNull(message = "필수예요.") Boolean sick,
        @NotNull(message = "필수예요.") Boolean caffeine) {
}
