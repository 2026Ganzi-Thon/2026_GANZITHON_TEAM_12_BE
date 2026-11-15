package com.comedown.experience.suggestions.api;

import com.comedown.experience.suggestions.application.PreStartGenerationFlow;
import com.comedown.experience.suggestions.application.SuggestionApprovalFlow;
import com.comedown.recommendation.contract.SuggestionPayload;
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

    SuggestionFlowController(PreStartGenerationFlow generation, SuggestionApprovalFlow approval,
            SuggestionQuery query, ServiceTime time) {
        this.generation = generation;
        this.approval = approval;
        this.query = query;
        this.time = time;
    }

    @PostMapping("/api/suggestions/generate")
    SuggestionsResponse generate(CurrentUser user, @Valid @RequestBody GenerateRequest body) {
        if ("RECOVERY".equals(body.type())) {
            throw new ApiException(ErrorCode.CALCULATION_POLICY_PENDING);
        }
        if (!"PRE_START".equals(body.type())) {
            throw FieldValidationException.of("type", "PRE_START 또는 RECOVERY여야 해요.");
        }
        generation.refresh(user.id());
        return new SuggestionsResponse(query.search(user.id(), "PRE_START", "PROPOSED", null).stream()
                .map(SuggestionPayload::of).toList());
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
                .body(SuggestionPayload.of(result.suggestion()));
    }

    @PostMapping("/api/suggestions/{id}/undo")
    SuggestionPayload undo(CurrentUser user, @PathVariable long id) {
        return SuggestionPayload.of(approval.undo(user.id(), id));
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
