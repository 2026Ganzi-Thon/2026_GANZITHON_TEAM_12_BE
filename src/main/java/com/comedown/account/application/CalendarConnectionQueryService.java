package com.comedown.account.application;

import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.account.infrastructure.persistence.CalendarConnectionReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarConnectionQueryService implements CalendarConnectionQuery {

    private final CalendarConnectionReader reader;

    public CalendarConnectionQueryService(CalendarConnectionReader reader) {
        this.reader = reader;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isConnected(long userId) {
        return reader.hasCalendarScope(userId);
    }
}
