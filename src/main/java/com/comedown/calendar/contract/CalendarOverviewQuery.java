package com.comedown.calendar.contract;

/** 동기화 시각·분류 현황 읽기 계약. */
public interface CalendarOverviewQuery {

    CalendarOverview overview(long userId);
}
