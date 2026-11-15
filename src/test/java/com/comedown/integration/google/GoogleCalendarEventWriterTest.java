package com.comedown.integration.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.comedown.account.contract.CalendarCredential;
import com.comedown.calendar.contract.CalendarEventWriter.NewEvent;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 등록 결과 해석: 같은 id가 이미 있다는 응답 뒤의 조회 결과에 따라 성공·확정 실패·결과 모름을 구분한다. */
class GoogleCalendarEventWriterTest {

    private static final String KEY = "cd0123456789abcdefghijklmnopqrstuv";
    private static final CalendarCredential CREDENTIAL = new CalendarCredential("token");
    private static final NewEvent EVENT = new NewEvent(KEY, "과제 미리 시작", Instant.parse("2026-11-16T22:00:00Z"),
            Instant.parse("2026-11-16T23:00:00Z"));

    private final GoogleCalendarClient client = mock(GoogleCalendarClient.class);
    private final GoogleCalendarEventWriter writer = new GoogleCalendarEventWriter(client);

    @Test
    void 새로_만들었으면_조회하지_않고_등록_키를_돌려준다() {
        when(client.insert(eq("token"), eq(KEY), any(), any(), any())).thenReturn(true);

        assertThat(writer.register(CREDENTIAL, EVENT)).isEqualTo(KEY);
        verify(client, never()).get(any(), any());
    }

    @Test
    void 이미_있는_id이고_일정이_살아_있으면_성공이다() {
        when(client.insert(any(), any(), any(), any(), any())).thenReturn(false);
        when(client.get("token", KEY)).thenReturn(Optional.of(mock(GoogleEvent.class)));

        assertThat(writer.register(CREDENTIAL, EVENT)).isEqualTo(KEY);
    }

    @Test
    void 이미_있는_id인데_삭제된_일정이면_확정_실패다() {
        when(client.insert(any(), any(), any(), any(), any())).thenReturn(false);
        when(client.get("token", KEY)).thenReturn(Optional.empty());

        assertKind(Kind.FAILURE);
    }

    @Test
    void 이미_있는_id인데_조회가_실패하면_인증_오류여도_결과_모름이다() {
        when(client.insert(any(), any(), any(), any(), any())).thenReturn(false);
        when(client.get("token", KEY)).thenThrow(new ExternalServiceException(Kind.UNAUTHORIZED, "401"));

        assertKind(Kind.UNCERTAIN);
    }

    private void assertKind(Kind expected) {
        assertThatThrownBy(() -> writer.register(CREDENTIAL, EVENT))
                .isInstanceOfSatisfying(ExternalServiceException.class,
                        e -> assertThat(e.kind()).isEqualTo(expected));
    }
}
