package com.comedown.schedule.application;

import com.comedown.calendar.contract.CalendarEventSnapshot;
import com.comedown.schedule.domain.LoadCategory;
import com.comedown.schedule.domain.LoadEvent;

/** calendar 계약 스냅샷을 부하 계산 입력으로 옮긴다. */
final class LoadEventMapper {

    private LoadEventMapper() {
    }

    static LoadEvent toLoadEvent(CalendarEventSnapshot s) {
        LoadCategory category = s.category() == null ? null : LoadCategory.valueOf(s.category());
        return new LoadEvent(s.start(), s.end(), s.startDate(), s.endDateExclusive(), s.allDay(),
                category, s.deadline(), s.createdByApp(), s.transparent(), s.cancelled());
    }
}
