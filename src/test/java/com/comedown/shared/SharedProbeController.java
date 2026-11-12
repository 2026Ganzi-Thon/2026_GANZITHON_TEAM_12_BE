package com.comedown.shared;

import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.security.CurrentUser;
import com.comedown.shared.time.ServiceTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 공통 기반(오류·인증 사용자·시간)을 검증하기 위한 테스트 전용 Controller. */
@RestController
class SharedProbeController {

    private final ServiceTime serviceTime;

    SharedProbeController(ServiceTime serviceTime) {
        this.serviceTime = serviceTime;
    }

    record MeResponse(long userId) {
    }

    record NicknameRequest(@NotBlank @Size(max = 20, message = "1~20자여야 합니다.") String nickname) {
    }

    record TimeResponse(OffsetDateTime now, LocalDate today, LocalDate weekStart, String missing) {
    }

    @GetMapping("/api/test/me")
    MeResponse me(CurrentUser user) {
        return new MeResponse(user.id());
    }

    @PostMapping("/api/test/nickname")
    NicknameRequest nickname(@Valid @RequestBody NicknameRequest request) {
        return request;
    }

    @PostMapping("/api/test/idempotent")
    String idempotent(@RequestHeader("Idempotency-Key") UUID key) {
        return key.toString();
    }

    @GetMapping("/api/test/conflict")
    void conflict() {
        throw new ApiException(ErrorCode.SLOT_CONFLICT);
    }

    @GetMapping("/api/test/boom")
    void boom() {
        throw new IllegalStateException("unexpected");
    }

    @GetMapping("/api/test/time")
    TimeResponse time() {
        return new TimeResponse(
                serviceTime.toResponse(serviceTime.now()),
                serviceTime.today(),
                serviceTime.currentWeekStart(),
                null);
    }
}
