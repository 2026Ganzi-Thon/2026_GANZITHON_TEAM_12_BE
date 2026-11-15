package com.comedown.experience.calendarflow.application;

import com.comedown.calendar.contract.CalendarCategoryCommands;
import com.comedown.calendar.contract.CategoryChange;
import com.comedown.calendar.contract.CategoryChangeResult;
import com.comedown.schedule.contract.WeekLoadView;
import com.comedown.schedule.contract.WeekLoads;
import java.util.List;
import org.springframework.stereotype.Service;

/** {@code PATCH /api/calendar/events/{id}/category}: 분류 규칙 저장·적용 → 영향 주 부하 재계산. */
@Service
public class CategoryChangeFlow {

    public record Result(int updatedCount, List<WeekLoadView> weeks) {
    }

    private final CalendarCategoryCommands calendar;
    private final WeekLoads weekLoads;

    public CategoryChangeFlow(CalendarCategoryCommands calendar, WeekLoads weekLoads) {
        this.calendar = calendar;
        this.weekLoads = weekLoads;
    }

    public Result change(long userId, long eventId, CategoryChange change) {
        CategoryChangeResult changed = calendar.change(userId, eventId, change);
        List<WeekLoadView> weeks = changed.affectedWeekStarts().isEmpty()
                ? List.of() : weekLoads.recalculate(userId, changed.affectedWeekStarts());
        return new Result(changed.updatedCount(), weeks);
    }
}
