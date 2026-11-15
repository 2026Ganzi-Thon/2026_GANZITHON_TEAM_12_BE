package com.comedown.cbi.api;

import com.comedown.cbi.contract.CbiQuery;
import com.comedown.cbi.contract.CbiResult;
import com.comedown.shared.security.CurrentUser;
import com.comedown.shared.time.ServiceTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/cbi/assessments} → 200. 내 CBI 응답 이력(최신순, HTTP 책임: cbi.api).
 * 원래 선택 없이 점수(소수 첫째 자리)·수준만 내려준다. 로그인 사용자 본인 것만 조회한다.
 */
@RestController
public class CbiHistoryController {

    private final CbiQuery cbiQuery;
    private final ServiceTime serviceTime;

    public CbiHistoryController(CbiQuery cbiQuery, ServiceTime serviceTime) {
        this.cbiQuery = cbiQuery;
        this.serviceTime = serviceTime;
    }

    public record SubscaleView(BigDecimal score, String level) {

        public static SubscaleView of(BigDecimal score, String level) {
            return new SubscaleView(score.setScale(1, RoundingMode.HALF_UP), level);
        }
    }

    record AssessmentView(long id, String round, OffsetDateTime takenAt, SubscaleView personal, SubscaleView work,
            boolean highRisk) {
    }

    record HistoryResponse(List<AssessmentView> assessments) {
    }

    @GetMapping("/api/cbi/assessments")
    public HistoryResponse history(CurrentUser user) {
        return new HistoryResponse(cbiQuery.findHistory(user.id()).stream().map(this::toView).toList());
    }

    private AssessmentView toView(CbiResult r) {
        return new AssessmentView(r.id(), r.round(), serviceTime.toResponse(r.takenAt()),
                SubscaleView.of(r.personalScore(), r.personalLevel()),
                SubscaleView.of(r.workScore(), r.workLevel()),
                r.highRisk());
    }
}
