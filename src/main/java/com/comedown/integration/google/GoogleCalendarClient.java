package com.comedown.integration.google;

import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Google Calendar API 호출(기본 캘린더 {@code primary}의 일정 조회). 토큰은 호출마다 인자로 받고 보관하지 않는다. */
@Component
public class GoogleCalendarClient {

    /** Come-down이 넣은 일정에 다는 표식 키({@code extendedProperties.private}). */
    public static final String APP_MARKER = "comedownApp";

    private static final int PAGE_SIZE = 250;
    private static final int MAX_PAGES = 40;

    private final RestClient client;

    @Autowired
    public GoogleCalendarClient(
            @Value("${comedown.google.calendar-api-url:https://www.googleapis.com/calendar/v3}") String baseUrl) {
        this(GoogleHttp.newBuilder(), baseUrl);
    }

    /** 테스트에서 가짜 서버를 연결한 빌더를 넣을 때 쓴다. */
    GoogleCalendarClient(RestClient.Builder builder, String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    /**
     * {@code [timeMin, timeMax)}와 겹치는 일정을 반복 일정은 회차별로 펼쳐 모두 읽는다(삭제된 일정은 제외).
     */
    public List<GoogleEvent> listEvents(String accessToken, Instant timeMin, Instant timeMax) {
        List<GoogleEvent> events = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> body = fetchPage(accessToken, timeMin, timeMax, pageToken);
            if (body != null && body.get("items") instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> raw) {
                        GoogleEvent event = parse(raw);
                        if (event != null) {
                            events.add(event);
                        }
                    }
                }
            }
            pageToken = body != null && body.get("nextPageToken") instanceof String t && !t.isBlank() ? t : null;
            if (pageToken == null) {
                return events;
            }
        }
        throw new ExternalServiceException(Kind.FAILURE, "일정이 너무 많아 모두 읽지 못했습니다.");
    }

    private Map<String, Object> fetchPage(String accessToken, Instant timeMin, Instant timeMax, String pageToken) {
        try {
            return client.get()
                    .uri(uri -> {
                        uri.path("/calendars/primary/events")
                                .queryParam("singleEvents", "true")
                                .queryParam("showDeleted", "false")
                                .queryParam("orderBy", "startTime")
                                .queryParam("maxResults", PAGE_SIZE)
                                .queryParam("timeMin", timeMin.toString())
                                .queryParam("timeMax", timeMax.toString());
                        if (pageToken != null) {
                            uri.queryParam("pageToken", pageToken);
                        }
                        return uri.build();
                    })
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new ExternalServiceException(Kind.UNAUTHORIZED, "캘린더 인증이 만료됐습니다.");
            }
            if (status == 403 && e.getResponseBodyAsString().toLowerCase().contains("insufficient")) {
                throw new ExternalServiceException(Kind.INSUFFICIENT_SCOPE, "캘린더 권한 범위가 부족합니다.");
            }
            throw new ExternalServiceException(Kind.FAILURE, "캘린더 조회에 실패했습니다(status=" + status + ").");
        } catch (RestClientException e) {
            throw new ExternalServiceException(Kind.FAILURE, "캘린더 조회 요청에 실패했습니다.", e);
        }
    }

    /** 시작·종료를 알 수 없는 일정은 null(건너뜀). */
    @SuppressWarnings("unchecked")
    static GoogleEvent parse(Map<?, ?> raw) {
        Object id = raw.get("id");
        Map<String, Object> start = raw.get("start") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        Map<String, Object> end = raw.get("end") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        if (!(id instanceof String eventId) || start == null || end == null) {
            return null;
        }
        boolean allDay = start.get("date") instanceof String;
        Instant startAt = null;
        Instant endAt = null;
        LocalDate startDate = null;
        LocalDate endDate = null;
        try {
            if (allDay) {
                startDate = LocalDate.parse((String) start.get("date"));
                endDate = LocalDate.parse((String) end.get("date"));
            } else {
                startAt = OffsetDateTime.parse((String) start.get("dateTime")).toInstant();
                endAt = OffsetDateTime.parse((String) end.get("dateTime")).toInstant();
            }
        } catch (RuntimeException e) {
            return null;
        }
        boolean app = false;
        if (raw.get("extendedProperties") instanceof Map<?, ?> ext && ext.get("private") instanceof Map<?, ?> priv) {
            app = "true".equals(String.valueOf(priv.get(APP_MARKER)));
        }
        Instant updated = null;
        if (raw.get("updated") instanceof String u) {
            try {
                updated = OffsetDateTime.parse(u).toInstant();
            } catch (RuntimeException ignored) {
                // 갱신 시각은 참고값이라 못 읽어도 무시한다.
            }
        }
        return new GoogleEvent(eventId,
                raw.get("recurringEventId") instanceof String r ? r : null,
                raw.get("summary") instanceof String s ? s : null,
                allDay, startAt, endAt, startDate, endDate,
                raw.get("status") instanceof String st ? st : "confirmed",
                raw.get("transparency") instanceof String t ? t : "opaque",
                app, updated);
    }
}
