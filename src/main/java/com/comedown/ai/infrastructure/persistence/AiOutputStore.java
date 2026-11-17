package com.comedown.ai.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** ai_outputs 읽기·쓰기(ai 소유). DATETIME(6)은 UTC. 캐시 키는 (user_id, kind, ref_date, input_hash). */
@Repository
public class AiOutputStore {

    /** @param model 사용 모델. 대체 문구면 null */
    public record Row(String content, String model, boolean fallback, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public AiOutputStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(long userId, String kind, LocalDate refDate, String inputHash) {
        return jdbc.sql("""
                        SELECT content, model, is_fallback, created_at FROM ai_outputs
                         WHERE user_id = ? AND kind = ? AND ref_date = ? AND input_hash = ?
                        """)
                .params(userId, kind, refDate, inputHash)
                .query((rs, n) -> new Row(rs.getString("content"), rs.getString("model"),
                        rs.getBoolean("is_fallback"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                .optional();
    }

    /** 같은 키가 있으면 내용을 바꾼다(대체 문구를 나중에 AI 문구로 바꿀 때). */
    public void save(long userId, String kind, LocalDate refDate, String inputHash, String content, String model,
            boolean fallback, Instant now) {
        jdbc.sql("""
                        INSERT INTO ai_outputs (user_id, kind, ref_date, input_hash, content, model, is_fallback,
                            created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) AS v
                        ON DUPLICATE KEY UPDATE content = v.content, model = v.model, is_fallback = v.is_fallback,
                            created_at = v.created_at
                        """)
                .params(userId, kind, refDate, inputHash, content, model, fallback,
                        LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public int deleteAll(long userId) {
        return jdbc.sql("DELETE FROM ai_outputs WHERE user_id = ?").params(userId).update();
    }
}
