package com.comedown.recommendation.api;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.recommendation.contract.SuggestionCommands;
import com.comedown.recommendation.contract.SuggestionPayload;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.recommendation.domain.SuggestionStatus;
import com.comedown.recommendation.domain.SuggestionType;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ErrorResponse.FieldError;
import com.comedown.shared.error.FieldValidationException;
import com.comedown.shared.security.CurrentUser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제안 조회·건너뛰기·했어요(HTTP 책임: recommendation.api). 생성·다른 시간·승인·되돌리기는 experience.suggestions(일정축).
 * <ul>
 *   <li>{@code GET /api/suggestions?type&status&date} → 200 {@code {suggestions: [Suggestion]}}. 모든 파라미터 선택.
 *       RECOVERY 추천은 온보딩 완료 후(403), PRE_START는 온보딩 중에도 조회</li>
 *   <li>{@code POST /api/suggestions/{id}/dismiss} → 200 최신 Suggestion(PROPOSED에서만, 반복은 같은 결과)</li>
 *   <li>{@code POST /api/suggestions/{id}/done} → 200 최신 Suggestion(APPROVED·일정 종료 후, doneAt만 채움)</li>
 * </ul>
 * 남의 제안 id는 404, 그 외 잘못된 전이는 409 INVALID_STATE.
 */
@RestController
public class SuggestionController {

    private final SuggestionQuery query;
    private final SuggestionCommands commands;
    private final AccountAccessQuery accountAccess;

    public SuggestionController(SuggestionQuery query, SuggestionCommands commands,
            AccountAccessQuery accountAccess) {
        this.query = query;
        this.commands = commands;
        this.accountAccess = accountAccess;
    }

    record SuggestionsResponse(List<SuggestionPayload> suggestions) {
    }

    @GetMapping("/api/suggestions")
    public SuggestionsResponse search(
            CurrentUser user,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        validate(type, status);
        if (SuggestionType.RECOVERY.name().equals(type)) {
            HealthDataAccess access = accountAccess.findHealthDataAccess(user.id())
                    .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
            if (!access.onboardingCompleted()) {
                throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
            }
        }
        return new SuggestionsResponse(query.search(user.id(), type, status, date).stream()
                .map(SuggestionPayload::of)
                .toList());
    }

    @PostMapping("/api/suggestions/{id}/dismiss")
    public SuggestionPayload dismiss(CurrentUser user, @PathVariable long id) {
        return SuggestionPayload.of(commands.dismiss(user.id(), id));
    }

    @PostMapping("/api/suggestions/{id}/done")
    public SuggestionPayload done(CurrentUser user, @PathVariable long id) {
        return SuggestionPayload.of(commands.markDone(user.id(), id));
    }

    private static void validate(String type, String status) {
        List<FieldError> errors = new ArrayList<>();
        if (type != null && Arrays.stream(SuggestionType.values()).noneMatch(t -> t.name().equals(type))) {
            errors.add(new FieldError("type", "PRE_START 또는 RECOVERY여야 해요."));
        }
        if (status != null && Arrays.stream(SuggestionStatus.values()).noneMatch(s -> s.name().equals(status))) {
            errors.add(new FieldError("status", "알 수 없는 상태예요."));
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }
}
