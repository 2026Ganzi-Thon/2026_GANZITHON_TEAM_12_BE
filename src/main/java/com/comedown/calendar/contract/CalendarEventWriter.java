package com.comedown.calendar.contract;

import com.comedown.account.contract.CalendarCredential;
import java.time.Instant;

/**
 * 구글 캘린더에 Come-down 일정을 쓰는 포트(전송은 integration.google이 구현). 일정 id를 호출하는 쪽이 정하는 등록 키로
 * 고정해 같은 요청을 다시 보내도 중복 일정이 생기지 않는다. 결과를 알 수 없는 실패(타임아웃·5xx)는
 * {@link com.comedown.shared.error.ExternalServiceException.Kind#UNCERTAIN}으로, 확정된 거절은 FAILURE로 알린다.
 */
public interface CalendarEventWriter {

    /** @param registrationKey 구글 event id로 쓰는 등록 키(base32hex 소문자 문자만) */
    record NewEvent(String registrationKey, String title, Instant start, Instant end) {
    }

    enum DeleteResult { DELETED, ALREADY_GONE, NOT_APP_OWNED }

    /**
     * 등록 키를 id로 일정을 만든다. 이미 있으면(이전 시도가 반영됨) 만들지 않고 그 id를 돌려준다.
     *
     * @return 구글 event id
     */
    String register(CalendarCredential credential, NewEvent event);

    /** 그 id의 일정이 구글에 살아 있는지(삭제됐으면 false). */
    boolean exists(CalendarCredential credential, String googleEventId);

    /** Come-down이 만든 일정(표식 확인)만 지운다. 이미 없으면 ALREADY_GONE, 우리 일정이 아니면 지우지 않는다. */
    DeleteResult deleteAppEvent(CalendarCredential credential, String googleEventId);
}
