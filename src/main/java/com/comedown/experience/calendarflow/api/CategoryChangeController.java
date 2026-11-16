package com.comedown.experience.calendarflow.api;

import com.comedown.calendar.contract.CategoryChange;
import com.comedown.experience.calendarflow.application.CategoryChangeFlow;
import com.comedown.schedule.contract.WeekLoadView;
import com.comedown.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code PATCH /api/calendar/events/{id}/category} → 200 (HTTP 책임: experience.calendarflow). */
@RestController
class CategoryChangeController {

    private final CategoryChangeFlow flow;

    CategoryChangeController(CategoryChangeFlow flow) {
        this.flow = flow;
    }

    @PatchMapping("/api/calendar/events/{id}/category")
    ChangeResponse change(CurrentUser user, @PathVariable long id, @Valid @RequestBody ChangeRequest body) {
        CategoryChangeFlow.Result r = flow.change(user.id(), id,
                new CategoryChange(body.category(), body.burden(), body.adjustable(), body.isDeadline(),
                        body.scope(), body.customCategory()));
        return new ChangeResponse(r.updatedCount(), r.weeks());
    }

    record ChangeRequest(
            @NotBlank String category,
            @Min(1) @Max(3) Integer burden,
            Boolean adjustable,
            @NotNull Boolean isDeadline,
            @NotBlank String scope,
            String customCategory) {
    }

    record ChangeResponse(int updatedCount, List<WeekLoadView> weeks) {
    }
}
