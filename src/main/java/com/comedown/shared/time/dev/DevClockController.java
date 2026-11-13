package com.comedown.shared.time.dev;

import com.comedown.shared.time.AdjustableClock;
import com.comedown.shared.time.ServiceTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/dev/clock} → 200. local 프로필 전용 서버 기준 날짜 이동(28일차 시연).
 * 서울 기준 오늘을 요청 날짜로 옮기고 시각은 실제 시계를 따른다. 실제 오늘을 보내면 원래대로 돌아간다.
 * 서버 전체 시계라 모든 사용자에게 적용된다. 운영 프로필에서는 빈이 만들어지지 않는다.
 */
@RestController
@Profile("local")
public class DevClockController {

    private static final Logger log = LoggerFactory.getLogger(DevClockController.class);

    private final AdjustableClock clock;
    private final ServiceTime serviceTime;

    public DevClockController(AdjustableClock clock, ServiceTime serviceTime) {
        this.clock = clock;
        this.serviceTime = serviceTime;
    }

    record DevClockRequest(@NotNull LocalDate date) {
    }

    /**
     * @param today      이동 후 서울 기준 오늘
     * @param now        이동 후 현재 시각(서울 오프셋)
     * @param offsetDays 실제 날짜와의 차이(일)
     */
    record DevClockResponse(LocalDate today, OffsetDateTime now, long offsetDays) {
    }

    @PostMapping("/api/dev/clock")
    public DevClockResponse move(@Valid @RequestBody DevClockRequest body) {
        long offsetDays = clock.moveTo(body.date());
        log.info("Local dev clock moved: today={}, offsetDays={}", body.date(), offsetDays);
        return new DevClockResponse(serviceTime.today(), serviceTime.toResponse(serviceTime.now()), offsetDays);
    }
}
