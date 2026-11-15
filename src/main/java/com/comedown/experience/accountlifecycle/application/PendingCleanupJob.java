package com.comedown.experience.accountlifecycle.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 구글 철회가 실패해 남은 탈퇴·연결 해제를 주기적으로 이어서 끝낸다(같은 앱 안의 작업, 메시지 브로커 없음). */
@Component
class PendingCleanupJob {

    private final AccountLifecycleFlow flow;

    PendingCleanupJob(AccountLifecycleFlow flow) {
        this.flow = flow;
    }

    @Scheduled(fixedDelayString = "${comedown.lifecycle.retry-delay:PT5M}",
            initialDelayString = "${comedown.lifecycle.retry-delay:PT5M}")
    void run() {
        flow.retryPending();
    }
}
