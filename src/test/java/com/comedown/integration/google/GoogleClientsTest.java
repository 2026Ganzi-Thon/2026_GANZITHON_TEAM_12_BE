package com.comedown.integration.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GoogleClientsTest {

    private static final Instant MIN = Instant.parse("2026-10-11T15:00:00Z");
    private static final Instant MAX = Instant.parse("2026-12-13T15:00:00Z");
    private static final String BASE = "http://google.test/calendar/v3";

    private final RestClient.Builder calendarBuilder = RestClient.builder();
    private final MockRestServiceServer calendarServer = MockRestServiceServer.bindTo(calendarBuilder).build();
    private final GoogleCalendarClient calendar = new GoogleCalendarClient(calendarBuilder, BASE);

    private static final String TIMED = """
            {"id":"e1","status":"confirmed","summary":"캡스톤 발표","recurringEventId":"series-1",
             "transparency":"opaque","updated":"2026-11-10T01:00:00.000Z",
             "start":{"dateTime":"2026-11-18T14:00:00+09:00"},"end":{"dateTime":"2026-11-18T15:30:00+09:00"}}""";
    private static final String ALL_DAY = """
            {"id":"e2","summary":"과제 마감","start":{"date":"2026-11-20"},"end":{"date":"2026-11-21"},
             "transparency":"transparent",
             "extendedProperties":{"private":{"comedownApp":"true"}}}""";

    @Test
    void 일정을_읽어_시간_일정과_종일_일정을_구분해_돌려준다() {
        calendarServer.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendars/primary/events")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andExpect(queryParam("singleEvents", "true"))
                .andExpect(queryParam("timeMin", "2026-10-11T15:00:00Z"))
                .andExpect(queryParam("timeMax", "2026-12-13T15:00:00Z"))
                .andRespond(withSuccess("{\"items\":[" + TIMED + "," + ALL_DAY + "]}", MediaType.APPLICATION_JSON));

        List<GoogleEvent> events = calendar.listEvents("token-1", MIN, MAX);

        assertThat(events).hasSize(2);
        GoogleEvent timed = events.get(0);
        assertThat(timed.title()).isEqualTo("캡스톤 발표");
        assertThat(timed.allDay()).isFalse();
        assertThat(timed.start()).isEqualTo(Instant.parse("2026-11-18T05:00:00Z"));
        assertThat(timed.end()).isEqualTo(Instant.parse("2026-11-18T06:30:00Z"));
        assertThat(timed.recurringEventId()).isEqualTo("series-1");
        assertThat(timed.createdByApp()).isFalse();
        GoogleEvent allDay = events.get(1);
        assertThat(allDay.allDay()).isTrue();
        assertThat(allDay.startDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(allDay.endDateExclusive()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(allDay.transparency()).isEqualTo("transparent");
        assertThat(allDay.createdByApp()).isTrue();
        calendarServer.verify();
    }

    @Test
    void 다음_페이지가_있으면_모두_읽는다() {
        calendarServer.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendars/primary/events")))
                .andRespond(withSuccess("{\"items\":[" + TIMED + "],\"nextPageToken\":\"p2\"}",
                        MediaType.APPLICATION_JSON));
        calendarServer.expect(queryParam("pageToken", "p2"))
                .andRespond(withSuccess("{\"items\":[" + ALL_DAY + "]}", MediaType.APPLICATION_JSON));

        assertThat(calendar.listEvents("t", MIN, MAX)).extracting(GoogleEvent::id).containsExactly("e1", "e2");
        calendarServer.verify();
    }

    @Test
    void 시작_종료를_읽을_수_없는_항목은_건너뛴다() {
        calendarServer.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendars/primary/events")))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"bad\"}," + TIMED + "]}", MediaType.APPLICATION_JSON));

        assertThat(calendar.listEvents("t", MIN, MAX)).extracting(GoogleEvent::id).containsExactly("e1");
    }

    @Test
    void 인증_실패_권한_부족_그_외를_구분한다() {
        calendarServer.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendars/primary/events")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> calendar.listEvents("t", MIN, MAX))
                .isInstanceOfSatisfying(ExternalServiceException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAUTHORIZED));

        calendarServer.reset();
        calendarServer.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendars/primary/events")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Request had insufficient authentication scopes.\"}}"));
        assertThatThrownBy(() -> calendar.listEvents("t", MIN, MAX))
                .isInstanceOfSatisfying(ExternalServiceException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.INSUFFICIENT_SCOPE));

        calendarServer.reset();
        calendarServer.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendars/primary/events")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> calendar.listEvents("secret-token", MIN, MAX))
                .isInstanceOfSatisfying(ExternalServiceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.FAILURE);
                    assertThat(e.getMessage()).doesNotContain("secret-token");
                });
    }

    // ---- OAuth 토큰 갱신 ----

    private final RestClient.Builder oauthBuilder = RestClient.builder();
    private final MockRestServiceServer oauthServer = MockRestServiceServer.bindTo(oauthBuilder).build();
    private final GoogleOAuthClient oauth = new GoogleOAuthClient(oauthBuilder, "http://google.test/token", "cid", "csecret", "http://google.test/revoke");
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    void refresh_token으로_새_access_token을_받는다() {
        oauthServer.expect(requestTo("http://google.test/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=refresh_token"),
                        org.hamcrest.Matchers.containsString("refresh_token=r-1"),
                        org.hamcrest.Matchers.containsString("client_id=cid"))))
                .andRespond(withSuccess("{\"access_token\":\"new-access\",\"expires_in\":3599,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));

        GoogleOAuthClient.RefreshedToken token = oauth.refresh("r-1", NOW);

        assertThat(token.accessToken()).isEqualTo("new-access");
        assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(3599));
    }

    @Test
    void 철회된_refresh_token은_UNAUTHORIZED_그_외는_FAILURE() {
        oauthServer.expect(requestTo("http://google.test/token")).andRespond(
                withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\"}"));
        assertThatThrownBy(() -> oauth.refresh("r-1", NOW)).isInstanceOfSatisfying(ExternalServiceException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.UNAUTHORIZED));

        oauthServer.reset();
        oauthServer.expect(requestTo("http://google.test/token")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> oauth.refresh("r-1", NOW)).isInstanceOfSatisfying(ExternalServiceException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.FAILURE));
    }

    @Test
    void 토큰_철회는_성공과_이미_철회된_400을_끝난_것으로_보고_그_외는_FAILURE() {
        oauthServer.expect(requestTo("http://google.test/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string("token=r-1"))
                .andRespond(withSuccess());
        oauth.revoke("r-1");

        oauthServer.reset();
        oauthServer.expect(requestTo("http://google.test/revoke")).andRespond(
                withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_token\"}"));
        oauth.revoke("r-1");

        oauthServer.reset();
        oauthServer.expect(requestTo("http://google.test/revoke")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> oauth.revoke("secret-token")).isInstanceOfSatisfying(ExternalServiceException.class,
                e -> {
                    assertThat(e.kind()).isEqualTo(Kind.FAILURE);
                    assertThat(e.getMessage()).doesNotContain("secret-token");
                });
    }

    @Test
    void 클라이언트가_설정되지_않으면_호출하지_않고_실패한다() {
        GoogleOAuthClient unconfigured = new GoogleOAuthClient(RestClient.builder(), "http://google.test/token", "", "", "http://google.test/revoke");

        assertThatThrownBy(() -> unconfigured.refresh("r", NOW)).isInstanceOf(ExternalServiceException.class);
    }
}
