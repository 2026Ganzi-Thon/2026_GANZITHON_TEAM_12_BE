package com.comedown.experience.suggestions.api;

import com.comedown.account.contract.AccountAccessQuery;
import com.comedown.account.contract.HealthDataAccess;
import com.comedown.experience.suggestions.application.PreStartGenerationFlow;
import com.comedown.experience.suggestions.application.RecoverySuggestionFlow;
import com.comedown.experience.suggestions.application.SuggestionApprovalFlow;
import com.comedown.experience.suggestions.application.SuggestionMessageAi;
import com.comedown.recommendation.contract.SuggestionPayload;
import com.comedown.recommendation.contract.SuggestionPayloads;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.FieldValidationException;
import com.comedown.shared.security.CurrentUser;
import com.comedown.shared.time.ServiceTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제안 생성·다른 시간·승인·되돌리기(HTTP 책임: experience.suggestions). 조회·건너뛰기·했어요는 recommendation.api.
 */
@RestController
class SuggestionFlowController {

    private final PreStartGenerationFlow generation;
    private final SuggestionApprovalFlow approval;
    private final SuggestionQuery query;
    private final ServiceTime time;
    private final SuggestionPayloads payloads;
    private final RecoverySuggestionFlow recovery;
    private final AccountAccessQuery accountAccess;
    private final SuggestionMessageAi messages;

    SuggestionFlowController(PreStartGenerationFlow generation, SuggestionApprovalFlow approval,
            SuggestionQuery query, ServiceTime time, SuggestionPayloads payloads, RecoverySuggestionFlow recovery,
            AccountAccessQuery accountAccess, SuggestionMessageAi messages) {
        this.generation = generation;
        this.approval = approval;
        this.query = query;
        this.time = time;
        this.payloads = payloads;
        this.recovery = recovery;
        this.accountAccess = accountAccess;
        this.messages = messages;
    }

    @PostMapping("/api/suggestions/generate")
    SuggestionsResponse generate(CurrentUser user, @Valid @RequestBody GenerateRequest body) {
        if ("RECOVERY".equals(body.type())) {
            return generateRecovery(user.id());
        }
        if (!"PRE_START".equals(body.type())) {
            throw FieldValidationException.of("type", "PRE_START 또는 RECOVERY여야 해요.");
        }
        generation.refresh(user.id());
        return new SuggestionsResponse(messages.apply(user.id(),
                payloads.of(user.id(), query.search(user.id(), "PRE_START", "PROPOSED", null))));
    }

    /** 오늘의 회복 추천: 온보딩 완료·건강정보 동의가 필요하다(회복 상태가 근거라서). */
    private SuggestionsResponse generateRecovery(long userId) {
        HealthDataAccess access = accountAccess.findHealthDataAccess(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!access.onboardingCompleted()) {
            throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
        }
        if (!access.sensitiveHrvConsented()) {
            throw new ApiException(ErrorCode.HRV_CONSENT_REQUIRED);
        }
        recovery.refresh(userId);
        return new SuggestionsResponse(messages.apply(userId,
                payloads.of(userId, query.search(userId, "RECOVERY", "PROPOSED", time.today()))));
    }

    @GetMapping("/api/suggestions/{id}/slots")
    SlotsResponse slots(CurrentUser user, @PathVariable long id) {
        return new SlotsResponse(approval.slots(user.id(), id).stream()
                .map(s -> new Slot(time.toResponse(s.start()), time.toResponse(s.end()))).toList());
    }

    @PostMapping("/api/suggestions/{id}/approve")
    ResponseEntity<SuggestionPayload> approve(CurrentUser user, @PathVariable long id,
            @RequestBody(required = false) ApproveRequest body) {
        var result = approval.approve(user.id(), id,
                body == null || body.start() == null ? null : body.start().toInstant(),
                body == null || body.end() == null ? null : body.end().toInstant());
        return ResponseEntity.status(result.confirmed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(payloads.of(user.id(), result.suggestion()));
    }

    @PostMapping("/api/suggestions/{id}/undo")
    SuggestionPayload undo(CurrentUser user, @PathVariable long id) {
        return payloads.of(user.id(), approval.undo(user.id(), id));
    }

    record GenerateRequest(@NotNull String type, String date) {
    }

    record ApproveRequest(OffsetDateTime start, OffsetDateTime end) {
    }

    record SuggestionsResponse(List<SuggestionPayload> suggestions) {
    }

    record Slot(OffsetDateTime start, OffsetDateTime end) {
    }

    record SlotsResponse(List<Slot> slots) {
    }
}
