package com.comedown.calendar.infrastructure.persistence;

import com.comedown.calendar.domain.Classification;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** calendar_events 읽기·쓰기(calendar 소유). DATETIME(6)은 UTC로 읽고 쓴다. 기본 캘린더(primary)만 다룬다. */
@Repository
public class CalendarEventStore {

    static final String CALENDAR_ID = "primary";
    private static final int IN_CHUNK = 500;

    private final JdbcClient jdbc;

    public CalendarEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<StoredEvent> findOverlapping(long userId, Instant from, Instant to) {
        return jdbc.sql(SELECT + " WHERE user_id = ? AND calendar_id = ? AND start_at < ? AND end_at > ? "
                        + "ORDER BY start_at, id")
                .params(userId, CALENDAR_ID, utc(to), utc(from))
                .query(CalendarEventStore::map).list();
    }

    public List<StoredEvent> findByGoogleIds(long userId, Collection<String> googleIds) {
        List<StoredEvent> result = new ArrayList<>();
        List<String> ids = new ArrayList<>(googleIds);
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            result.addAll(jdbc.sql(SELECT + " WHERE user_id = :user AND calendar_id = :cal AND google_event_id IN (:ids)")
                    .param("user", userId).param("cal", CALENDAR_ID)
                    .param("ids", ids.subList(i, Math.min(i + IN_CHUNK, ids.size())))
                    .query(CalendarEventStore::map).list());
        }
        return result;
    }

    /** 시작이 범위에 드는 과제 일정(id, 시작). */
    public List<StoredEvent> findAssignments(long userId, Instant from, Instant to) {
        return jdbc.sql(SELECT + " WHERE user_id = ? AND calendar_id = ? AND category = 'ASSIGNMENT' "
                        + "AND event_status <> 'cancelled' AND start_at >= ? AND start_at < ? ORDER BY start_at, id")
                .params(userId, CALENDAR_ID, utc(from), utc(to))
                .query(CalendarEventStore::map).list();
    }

    /** 사용자의 모든 일정을 지운다(제안의 일정 참조를 먼저 끊은 뒤). */
    public int deleteAll(long userId) {
        return jdbc.sql("DELETE FROM calendar_events WHERE user_id = ?").params(userId).update();
    }

    /** 마지막 동기화 시각과 (취소 제외) 전체·분류된 일정 수. */
    public Overview overview(long userId) {
        return jdbc.sql("""
                        SELECT MAX(synced_at) AS synced_at, COUNT(*) AS total, COUNT(category) AS classified
                          FROM calendar_events
                         WHERE user_id = ? AND calendar_id = ? AND event_status <> 'cancelled'
                        """)
                .params(userId, CALENDAR_ID)
                .query((rs, n) -> new Overview(rs.getTimestamp("synced_at") == null ? null
                        : rs.getTimestamp("synced_at").toLocalDateTime().toInstant(ZoneOffset.UTC),
                        rs.getInt("total"), rs.getInt("classified")))
                .single();
    }

    public record Overview(Instant syncedAt, int total, int classified) {
    }

    /** 사용자 본인의 일정만 찾는다(남의 id는 비어 있음). */
    public Optional<StoredEvent> findById(long userId, long id) {
        return jdbc.sql(SELECT + " WHERE user_id = ? AND id = ?").params(userId, id)
                .query(CalendarEventStore::map).optional();
    }

    public List<StoredEvent> findByRecurringId(long userId, String recurringEventId) {
        return jdbc.sql(SELECT + " WHERE user_id = ? AND calendar_id = ? AND recurring_event_id = ?")
                .params(userId, CALENDAR_ID, recurringEventId).query(CalendarEventStore::map).list();
    }

    public List<StoredEvent> findByTitleHash(long userId, String titleHash) {
        return jdbc.sql(SELECT + " WHERE user_id = ? AND calendar_id = ? AND title_hash = ?")
                .params(userId, CALENDAR_ID, titleHash).query(CalendarEventStore::map).list();
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public long insert(long userId, String googleEventId, String recurringEventId, String titleHash, Instant startAt,
            Instant endAt, boolean allDay, String status, String transparency, boolean createdByApp,
            Classification classification, Instant googleUpdatedAt, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO calendar_events (user_id, calendar_id, google_event_id, recurring_event_id,
                            title_hash, start_at, end_at, all_day, event_status, transparency, created_by_app,
                            category, category_source, burden, adjustable, is_deadline, classified_at,
                            google_updated_at, synced_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(userId, CALENDAR_ID, googleEventId, recurringEventId, titleHash, utc(startAt), utc(endAt),
                        allDay, status, transparency, createdByApp,
                        classification == null ? null : classification.category().name(),
                        classification == null ? null : classification.source().name(),
                        classification == null ? null : classification.burden(),
                        classification == null ? null : classification.adjustable(),
                        classification == null ? null : classification.deadline(),
                        classification == null ? null : utc(now),
                        googleUpdatedAt == null ? null : utc(googleUpdatedAt), utc(now))
                .update(keys);
        return keys.getKey().longValue();
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public void update(long id, String recurringEventId, String titleHash, Instant startAt, Instant endAt,
            boolean allDay, String status, String transparency, boolean createdByApp, Classification classification,
            Instant googleUpdatedAt, Instant now) {
        jdbc.sql("""
                        UPDATE calendar_events
                           SET recurring_event_id = ?, title_hash = ?, start_at = ?, end_at = ?, all_day = ?,
                               event_status = ?, transparency = ?, created_by_app = ?, category = ?,
                               category_source = ?, burden = ?, adjustable = ?, is_deadline = ?,
                               classified_at = ?, google_updated_at = ?, synced_at = ?
                         WHERE id = ?
                        """)
                .params(recurringEventId, titleHash, utc(startAt), utc(endAt), allDay, status, transparency,
                        createdByApp,
                        classification == null ? null : classification.category().name(),
                        classification == null ? null : classification.source().name(),
                        classification == null ? null : classification.burden(),
                        classification == null ? null : classification.adjustable(),
                        classification == null ? null : classification.deadline(),
                        classification == null ? null : utc(now),
                        googleUpdatedAt == null ? null : utc(googleUpdatedAt), utc(now), id)
                .update();
    }

    /** 분류만 바꾼다(직접 수정 규칙 적용). */
    public void updateClassification(long id, Classification classification, Instant now) {
        jdbc.sql("""
                        UPDATE calendar_events
                           SET category = ?, category_source = ?, burden = ?, adjustable = ?, is_deadline = ?,
                               classified_at = ?
                         WHERE id = ?
                        """)
                .params(classification.category().name(), classification.source().name(), classification.burden(),
                        classification.adjustable(), classification.deadline(), utc(now), id)
                .update();
    }

    /** 바뀌지 않은 일정의 동기화 시각만 갱신한다. */
    public void touch(Collection<Long> ids, Instant now) {
        List<Long> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            jdbc.sql("UPDATE calendar_events SET synced_at = :now WHERE id IN (:ids)")
                    .param("now", utc(now)).param("ids", list.subList(i, Math.min(i + IN_CHUNK, list.size())))
                    .update();
        }
    }

    /**
     * 구글에서 사라진 일정을 지운다. 다른 기능(제안)이 참조하는 일정({@code referenced})은 그쪽 참조를 건드리지 않도록
     * 지우지 않고 취소 상태로 바꾼다(부하 계산에서는 제외된다). 다른 기능의 테이블은 읽지 않는다.
     *
     * @return 지운 일정 수
     */
    public int removeOrCancel(long userId, Collection<Long> ids, Collection<Long> referenced) {
        List<Long> toCancel = new ArrayList<>();
        List<Long> toDelete = new ArrayList<>();
        for (Long id : ids) {
            (referenced.contains(id) ? toCancel : toDelete).add(id);
        }
        for (int i = 0; i < toCancel.size(); i += IN_CHUNK) {
            jdbc.sql("""
                            UPDATE calendar_events SET event_status = 'cancelled'
                             WHERE user_id = :user AND id IN (:ids) AND event_status <> 'cancelled'
                            """)
                    .param("user", userId)
                    .param("ids", toCancel.subList(i, Math.min(i + IN_CHUNK, toCancel.size())))
                    .update();
        }
        int deleted = 0;
        for (int i = 0; i < toDelete.size(); i += IN_CHUNK) {
            deleted += jdbc.sql("DELETE FROM calendar_events WHERE user_id = :user AND id IN (:ids)")
                    .param("user", userId)
                    .param("ids", toDelete.subList(i, Math.min(i + IN_CHUNK, toDelete.size())))
                    .update();
        }
        return deleted;
    }

    private static final String SELECT = """
            SELECT id, google_event_id, recurring_event_id, title_hash, start_at, end_at, all_day, event_status,
                   transparency, created_by_app, category, category_source, burden, adjustable, is_deadline,
                   google_updated_at
              FROM calendar_events""";

    private static StoredEvent map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new StoredEvent(rs.getLong("id"), rs.getString("google_event_id"), rs.getString("recurring_event_id"),
                rs.getString("title_hash"), instant(rs, "start_at"), instant(rs, "end_at"), rs.getBoolean("all_day"),
                rs.getString("event_status"), rs.getString("transparency"), rs.getBoolean("created_by_app"),
                rs.getString("category"), rs.getString("category_source"),
                rs.getObject("burden") == null ? null : rs.getInt("burden"),
                rs.getObject("adjustable") == null ? null : rs.getBoolean("adjustable"),
                rs.getObject("is_deadline") == null ? null : rs.getBoolean("is_deadline"),
                instant(rs, "google_updated_at"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
