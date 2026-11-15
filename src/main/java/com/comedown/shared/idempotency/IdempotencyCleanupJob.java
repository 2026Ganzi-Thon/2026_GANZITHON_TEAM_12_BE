package com.comedown.shared.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된(24시간 이상 지난) 완료 재시도 키를 주기적으로 지운다. 처리 중인 키는 남긴다.
 */
@Component
class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyService idempotencyService;

    IdempotencyCleanupJob(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Scheduled(cron = "${comedown.idempotency.cleanup-cron:0 17 * * * *}", zone = "Asia/Seoul")
    void purgeExpired() {
        int deleted = idempotencyService.purgeExpired();
        if (deleted > 0) {
            log.info("Expired idempotency keys deleted: {}", deleted);
        }
    }
}
