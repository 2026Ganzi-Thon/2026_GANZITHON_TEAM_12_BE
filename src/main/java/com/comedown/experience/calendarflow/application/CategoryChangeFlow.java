package com.comedown.experience.calendarflow.application;

import com.comedown.account.contract.AccountDeletion;
import com.comedown.calendar.contract.CalendarCategoryCommands;
import com.comedown.calendar.contract.CategoryChange;
import com.comedown.calendar.contract.CategoryChangeResult;
import com.comedown.schedule.contract.WeekLoadView;
import com.comedown.schedule.contract.WeekLoads;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code PATCH /api/calendar/events/{id}/category}: 분류 규칙 저장·적용 → 영향 주 부하 재계산. */
@Service
public class CategoryChangeFlow {

    public record Result(int updatedCount, List<WeekLoadView> weeks) {
    }

    private final CalendarCategoryCommands calendar;
    private final WeekLoads weekLoads;
    private final AccountDeletion lock;

    public CategoryChangeFlow(CalendarCategoryCommands calendar, WeekLoads weekLoads, AccountDeletion lock) {
        this.calendar = calendar;
        this.weekLoads = weekLoads;
        this.lock = lock;
    }

    /** 사용자 행을 잠가 연결 해제·탈퇴와 직렬화한다(삭제한 일정에 규칙이 남지 않게). */
    @Transactional
    public Result change(long userId, long eventId, CategoryChange change) {
        lock.lock(userId);
        CategoryChangeResult changed = calendar.change(userId, eventId, change);
        List<WeekLoadView> weeks = changed.affectedWeekStarts().isEmpty()
                ? List.of() : weekLoads.recalculate(userId, changed.affectedWeekStarts());
        return new Result(changed.updatedCount(), weeks);
    }
}
