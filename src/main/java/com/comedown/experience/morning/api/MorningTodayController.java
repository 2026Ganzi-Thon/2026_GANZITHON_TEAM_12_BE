package com.comedown.experience.morning.api;

import com.comedown.experience.morning.application.MorningAccessGuard;
import com.comedown.experience.morning.application.MorningCheckToday;
import com.comedown.experience.morning.application.MorningTodayQuery;
import com.comedown.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/morning-checks/today} → 200. 측정 탭 진입(HTTP 책임: experience.morning).
 * 온보딩 완료만 확인한다(403). 읽기 전용이라 체크 행을 만들지 않는다.
 */
@RestController
public class MorningTodayController {

    private final MorningAccessGuard accessGuard;
    private final MorningTodayQuery query;

    public MorningTodayController(MorningAccessGuard accessGuard, MorningTodayQuery query) {
        this.accessGuard = accessGuard;
        this.query = query;
    }

    @GetMapping("/api/morning-checks/today")
    public MorningCheckToday today(CurrentUser user) {
        return query.today(user.id(), accessGuard.requireOnboarded(user.id()));
    }
}
