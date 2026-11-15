package com.comedown.calendar.application;

import com.comedown.calendar.contract.CalendarOverview;
import com.comedown.calendar.contract.CalendarOverviewQuery;
import com.comedown.calendar.infrastructure.persistence.CalendarEventStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CalendarOverviewService implements CalendarOverviewQuery {

    private final CalendarEventStore store;

    CalendarOverviewService(CalendarEventStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public CalendarOverview overview(long userId) {
        CalendarEventStore.Overview o = store.overview(userId);
        return new CalendarOverview(o.syncedAt(), o.total(), o.classified());
    }
}
