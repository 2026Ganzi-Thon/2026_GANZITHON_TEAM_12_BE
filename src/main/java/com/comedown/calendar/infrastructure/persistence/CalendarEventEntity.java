package com.comedown.calendar.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * calendar_events 매핑. 현재는 읽기에 필요한 컬럼만 매핑한다(동기화 쓰기 구현 때 확장).
 */
@Entity
@Table(name = "calendar_events")
public class CalendarEventEntity {

    @Id
    private Long id;

    @Column(name = "user_id")
    private long userId;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "all_day")
    private boolean allDay;

    @Column(name = "event_status")
    private String eventStatus;

    private String transparency;

    @Column(name = "created_by_app")
    private boolean createdByApp;

    private String category;

    @Column(name = "is_deadline")
    private Boolean deadline;

    protected CalendarEventEntity() {
    }

    Instant startAt() { return startAt; }
    Instant endAt() { return endAt; }
    boolean allDay() { return allDay; }
    String eventStatus() { return eventStatus; }
    String transparency() { return transparency; }
    boolean createdByApp() { return createdByApp; }
    String category() { return category; }
    Boolean deadline() { return deadline; }
}
