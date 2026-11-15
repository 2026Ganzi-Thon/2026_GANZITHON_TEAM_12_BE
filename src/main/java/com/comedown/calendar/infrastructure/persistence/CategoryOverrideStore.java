package com.comedown.calendar.infrastructure.persistence;

import com.comedown.calendar.domain.CategoryOverride;
import com.comedown.calendar.domain.CategoryScope;
import com.comedown.calendar.domain.EventCategory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** event_category_overrides 읽기·쓰기(calendar 소유). */
@Repository
public class CategoryOverrideStore {

    private final JdbcClient jdbc;

    public CategoryOverrideStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteAll(long userId) {
        jdbc.sql("DELETE FROM event_category_overrides WHERE user_id = ?").params(userId).update();
    }

    public List<CategoryOverride> findAll(long userId) {
        return jdbc.sql("""
                        SELECT scope, match_key, category, burden, adjustable, is_deadline
                          FROM event_category_overrides WHERE user_id = ? AND calendar_id = ?
                        """)
                .params(userId, CalendarEventStore.CALENDAR_ID)
                .query((rs, n) -> new CategoryOverride(CategoryScope.valueOf(rs.getString("scope")),
                        rs.getString("match_key"), EventCategory.valueOf(rs.getString("category")),
                        rs.getObject("burden") == null ? null : rs.getInt("burden"),
                        rs.getObject("adjustable") == null ? null : rs.getBoolean("adjustable"),
                        rs.getBoolean("is_deadline")))
                .list();
    }

    /** 같은 (사용자, 캘린더, 범위, 키) 규칙이 있으면 덮어쓴다. */
    public void upsert(long userId, CategoryOverride rule, Instant now) {
        LocalDateTime at = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO event_category_overrides (user_id, calendar_id, scope, match_key, category,
                            burden, adjustable, is_deadline, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS v
                        ON DUPLICATE KEY UPDATE category = v.category, burden = v.burden,
                            adjustable = v.adjustable, is_deadline = v.is_deadline, updated_at = v.updated_at
                        """)
                .params(userId, CalendarEventStore.CALENDAR_ID, rule.scope().name(), rule.matchKey(),
                        rule.category().name(), rule.burden(), rule.adjustable(), rule.deadline(), at, at)
                .update();
    }
}
