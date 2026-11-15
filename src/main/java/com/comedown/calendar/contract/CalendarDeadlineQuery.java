package com.comedown.calendar.contract;

import java.time.Instant;
import java.util.List;

/** 마감으로 보는 일정(과제) 읽기 계약. 제목은 담지 않는다. */
public interface CalendarDeadlineQuery {

    /**
     * @param start 일정 시작(UTC). 종일 일정이면 서울 그 날 00:00
     */
    record Deadline(long eventId, Instant start) {
    }

    /** 시작이 {@code [from, to)}에 드는 과제(ASSIGNMENT) 일정(취소 제외), 시작 순. */
    List<Deadline> upcomingAssignments(long userId, Instant from, Instant to);
}
