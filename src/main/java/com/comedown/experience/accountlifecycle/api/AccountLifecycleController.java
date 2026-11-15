package com.comedown.experience.accountlifecycle.api;

import com.comedown.experience.accountlifecycle.application.AccountLifecycleFlow;
import com.comedown.experience.accountlifecycle.application.AccountLifecycleFlow.Outcome;
import com.comedown.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 삭제 API(HTTP 변환만): 건강 기록 삭제, 건강정보 동의 철회, 캘린더 연결 해제, 탈퇴.
 * 서로 다른 유스케이스이며 범위는 각 흐름이 정한다. 외부 정리가 남으면 202 + {@code {"status":"DELETING"}}.
 */
@RestController
class AccountLifecycleController {

    private final AccountLifecycleFlow flow;

    AccountLifecycleController(AccountLifecycleFlow flow) {
        this.flow = flow;
    }

    @DeleteMapping("/api/hrv/data")
    ResponseEntity<Void> eraseHealthData(CurrentUser user) {
        flow.eraseHealthData(user.id());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/me/consents/SENSITIVE_HRV")
    ResponseEntity<Void> withdrawHealthConsent(CurrentUser user,
            @RequestParam(defaultValue = "false") boolean deleteData) {
        flow.withdrawHealthConsent(user.id(), deleteData);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/calendar/connection")
    ResponseEntity<?> disconnectCalendar(CurrentUser user) {
        return respond(flow.disconnectCalendar(user.id()));
    }

    /**
     * 탈퇴를 시작하면 로그인 세션은 바로 끊기므로, 구글 철회가 남은 202에서도 세션·CSRF 쿠키를 지운다
     * (로그아웃과 같은 방식).
     */
    @DeleteMapping("/api/me")
    ResponseEntity<?> withdraw(CurrentUser user, HttpServletResponse response) {
        Outcome outcome = flow.withdraw(user.id());
        response.addHeader("Set-Cookie", "SESSION=; Path=/; Max-Age=0; HttpOnly");
        response.addHeader("Set-Cookie", "XSRF-TOKEN=; Path=/; Max-Age=0");
        return respond(outcome);
    }

    private static ResponseEntity<?> respond(Outcome outcome) {
        if (outcome == Outcome.PENDING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "DELETING"));
        }
        return ResponseEntity.noContent().build();
    }
}
