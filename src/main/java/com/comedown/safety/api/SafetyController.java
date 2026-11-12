package com.comedown.safety.api;

import com.comedown.safety.contract.SafetyAlertView;
import com.comedown.safety.contract.SafetyCommands;
import com.comedown.safety.contract.SafetyQuery;
import com.comedown.shared.security.CurrentUser;
import com.comedown.shared.time.ServiceTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C3 도움 받을 수 있는 곳(HTTP 책임: safety.api). 로그인만 필요하다(온보딩 중·동의 없이도 허용).
 * <ul>
 *   <li>{@code GET /api/safety} → 200: show(열려 있고 닫지 않은 안내가 있음), 열린 안내, 지원 기관, 고지 문구</li>
 *   <li>{@code POST /api/safety/alerts/{id}/acknowledge} → 204: 닫기(조건 해제와 다름). 남의 안내는 404</li>
 * </ul>
 */
@RestController
public class SafetyController {

    static final String DISCLAIMER = "Come-down은 의료 서비스가 아니며 질병 유무를 판단하지 않아요.";

    /** 안내 문구. LOW_RECOVERY_2W는 명세 문구, CBI_HIGH는 기획 확정 전 임시 문구. */
    static final Map<String, String> MESSAGES = Map.of(
            "LOW_RECOVERY_2W", "최근 2주 동안 회복이 평소보다 낮게 이어졌어요.",
            "CBI_HIGH", "번아웃 체크에서 피로 점수가 높게 나왔어요.");

    /**
     * 지원 기관. 명세의 구조용 placeholder다. 실제 연락처·링크는 출시 전 공식 기관 정보로 확인해 verifiedAt을 기록하고,
     * 확인하지 않은 항목은 phone·url을 비워 전화 버튼을 노출하지 않는다.
     */
    static final List<Resource> RESOURCES = List.of(
            new Resource("verified-support-resource", "공식 확인 후 등록한 지원 기관", null, null, null));

    private final SafetyQuery safetyQuery;
    private final SafetyCommands safetyCommands;
    private final ServiceTime serviceTime;

    public SafetyController(SafetyQuery safetyQuery, SafetyCommands safetyCommands, ServiceTime serviceTime) {
        this.safetyQuery = safetyQuery;
        this.safetyCommands = safetyCommands;
        this.serviceTime = serviceTime;
    }

    record Alert(long id, String triggerType, OffsetDateTime triggeredAt, String message) {
    }

    record Resource(String id, String name, String phone, String url, OffsetDateTime verifiedAt) {
    }

    record SafetyResponse(boolean show, List<Alert> alerts, List<Resource> resources, String disclaimer) {
    }

    @GetMapping("/api/safety")
    public SafetyResponse safety(CurrentUser user) {
        List<SafetyAlertView> open = safetyQuery.findOpenAlerts(user.id());
        boolean show = open.stream().anyMatch(a -> !a.acknowledged());
        List<Alert> alerts = open.stream()
                .map(a -> new Alert(a.id(), a.triggerType(), serviceTime.toResponse(a.triggeredAt()),
                        MESSAGES.get(a.triggerType())))
                .toList();
        return new SafetyResponse(show, alerts, RESOURCES, DISCLAIMER);
    }

    @PostMapping("/api/safety/alerts/{id}/acknowledge")
    public ResponseEntity<Void> acknowledge(CurrentUser user, @PathVariable long id) {
        safetyCommands.acknowledge(user.id(), id);
        return ResponseEntity.noContent().build();
    }
}
