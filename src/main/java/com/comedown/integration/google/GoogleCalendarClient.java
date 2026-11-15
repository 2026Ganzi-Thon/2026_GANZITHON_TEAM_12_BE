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

    /**
     * 고정 id로 일정을 만든다. 이미 있으면(409) 만들지 않고 false. 4xx 거절은 FAILURE, 타임아웃·5xx는 UNCERTAIN
     * (이미 만들어졌을 수 있으므로 호출하는 쪽이 조회로 확인한다).
     *
     * @return 새로 만들었으면 true
     */
    public boolean insert(String accessToken, String id, String summary, Instant start, Instant end) {
        Map<String, Object> body = Map.of(
                "id", id,
                "summary", summary,
                "start", Map.of("dateTime", start.toString()),
                "end", Map.of("dateTime", end.toString()),
                "extendedProperties", Map.of("private", Map.of(APP_MARKER, "true")));
        try {
            client.post().uri("/calendars/primary/events")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 409) {
                return false;
            }
            throw writeFailure(e, "일정 등록");
        } catch (RestClientException e) {
            throw new ExternalServiceException(Kind.UNCERTAIN, "일정 등록 요청의 결과를 알 수 없습니다.", e);
        }
    }

    /** 일정 한 건. 없거나(404) 삭제된(cancelled) 일정은 비어 있다. */
    public java.util.Optional<GoogleEvent> get(String accessToken, String id) {
        try {
            Map<String, Object> raw = client.get().uri("/calendars/primary/events/{id}", id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            GoogleEvent event = raw == null ? null : parse(raw);
            return event == null || event.cancelled() ? java.util.Optional.empty() : java.util.Optional.of(event);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404 || status == 410) {
                return java.util.Optional.empty();
            }
            throw writeFailure(e, "일정 조회");
        } catch (RestClientException e) {
            throw new ExternalServiceException(Kind.UNCERTAIN, "일정 조회 요청의 결과를 알 수 없습니다.", e);
        }
    }

    /** 일정을 지운다. 이미 없으면(404·410) 지운 것과 같게 본다. */
    public void delete(String accessToken, String id) {
        try {
            client.delete().uri("/calendars/primary/events/{id}", id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404 || status == 410) {
                return;
            }
            throw writeFailure(e, "일정 삭제");
        } catch (RestClientException e) {
            throw new ExternalServiceException(Kind.UNCERTAIN, "일정 삭제 요청의 결과를 알 수 없습니다.", e);
        }
    }

    /**
     * 쓰기·단건 조회 실패. 403은 권한 범위 부족일 때만 INSUFFICIENT_SCOPE이고, 사용량 제한(rateLimitExceeded 등)이나
     * 다른 거절은 요청을 처리하지 않았다는 응답이므로 FAILURE다. 429·5xx는 결과를 알 수 없어 UNCERTAIN.
     */
    private static ExternalServiceException writeFailure(RestClientResponseException e, String what) {
        int status = e.getStatusCode().value();
        if (status == 401) {
            return new ExternalServiceException(Kind.UNAUTHORIZED, "캘린더 인증이 만료됐습니다.");
        }
        if (isInsufficientScope(e)) {
            return new ExternalServiceException(Kind.INSUFFICIENT_SCOPE, "캘린더 권한 범위가 부족합니다.");
        }
        Kind kind = status >= 500 || status == 429 ? Kind.UNCERTAIN : Kind.FAILURE;
        return new ExternalServiceException(kind, what + "에 실패했습니다(status=" + status + ").");
    }

    /**
     * 403 중 권한 범위 부족만 고른다. Google은 범위 부족을 {@code insufficientPermissions} 또는
     * {@code ACCESS_TOKEN_SCOPE_INSUFFICIENT}("insufficient authentication scopes")로 알려 준다.
     */
    static boolean isInsufficientScope(RestClientResponseException e) {
        return e.getStatusCode().value() == 403
                && e.getResponseBodyAsString().toLowerCase(java.util.Locale.ROOT).contains("insufficient");
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
            if (isInsufficientScope(e)) {
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
