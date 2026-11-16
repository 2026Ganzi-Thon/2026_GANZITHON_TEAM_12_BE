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

    /** 안내 문구(와이어프레임 C3 문구). */
    static final Map<String, String> MESSAGES = Map.of(
            "LOW_RECOVERY_2W", "최근 2주 동안 회복이 평소보다 낮게 이어졌어요.",
            "CBI_HIGH", "번아웃 체크에서 많이 지쳐 있는 것으로 나왔어요.");

    /** 아래 지원 기관의 번호·주소를 보건복지부 공식 누리집에서 확인한 날(서울). */
    static final OffsetDateTime RESOURCES_VERIFIED_AT = OffsetDateTime.parse("2026-11-17T00:00:00+09:00");

    /**
     * 지원 기관. 번호와 주소는 공식 기관 누리집에서 확인한 것만 넣고 확인한 날을 verifiedAt으로 남긴다.
     * 확인하지 않은 항목은 넣지 않는다(전화 버튼을 잘못 노출하지 않게). 번호가 바뀌면 다시 확인하고 날짜를 고친다.
     * <ul>
     *   <li>자살예방상담전화 109: 보건복지부 129.go.kr/109 (24시간)</li>
     *   <li>정신건강 위기상담전화 1577-0199: 보건복지부 "정신건강 복지센터 및 정신건강상담전화 운영"</li>
     * </ul>
     */
    static final List<Resource> RESOURCES = List.of(
            new Resource("suicide-prevention-109", "자살예방상담전화", "109", "https://www.129.go.kr/109",
                    RESOURCES_VERIFIED_AT),
            new Resource("mental-health-crisis-1577-0199", "정신건강 위기상담전화", "1577-0199",
                    "https://www.mohw.go.kr/menu.es?mid=a10706040100", RESOURCES_VERIFIED_AT));

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
