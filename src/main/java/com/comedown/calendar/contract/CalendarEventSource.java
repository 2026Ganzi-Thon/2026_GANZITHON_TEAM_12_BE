package com.comedown.calendar.contract;

import com.comedown.account.contract.CalendarCredential;
import com.comedown.shared.time.InstantRange;
import java.util.List;

/**
 * 구글 캘린더에서 일정을 읽는 포트(전송은 integration.google이 구현). 범위와 겹치는 일정을 반복 일정은 회차별로
 * 펼쳐서 돌려주고, 삭제된 일정은 포함하지 않는다.
 * 실패는 {@link com.comedown.shared.error.ExternalServiceException}으로 알린다.
 */
public interface CalendarEventSource {

    List<SyncedEvent> fetch(CalendarCredential credential, InstantRange range);
}
