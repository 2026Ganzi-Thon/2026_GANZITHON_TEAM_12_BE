package com.comedown.calendar.contract;

/** 일정 분류 직접 수정 계약. */
public interface CalendarCategoryCommands {

    /**
     * 규칙을 저장하고 범위에 드는 저장된 일정에 적용한다. 본인 일정이 아니면 404, 반복 일정이 아닌데 SERIES거나
     * 제목 식별값이 없는데 TITLE이면 400이다.
     *
     * @param eventId calendar_events.id
     */
    CategoryChangeResult change(long userId, long eventId, CategoryChange change);
}
