package com.comedown.calendar.contract;

import com.comedown.shared.time.InstantRange;
import java.util.List;

/** 동기화된 일정을 다른 기능(schedule 등)에 읽기 전용으로 제공하는 계약. */
public interface CalendarEventReader {

    /** 범위와 겹치는 사용자의 일정(종일 일정 포함). */
    List<CalendarEventSnapshot> findOverlapping(long userId, InstantRange range);
}
