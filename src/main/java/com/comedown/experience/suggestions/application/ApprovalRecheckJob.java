package com.comedown.experience.suggestions.application;

import com.comedown.recommendation.contract.SuggestionApprovalCommands;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 외부 결과를 확인하지 못한 채 승인 중(APPROVING)으로 남은 제안을 주기적으로 같은 등록 키로 다시 확인·확정한다.
 * (같은 앱 안의 작업, 메시지 브로커 없음.)
 */
@Component
public class ApprovalRecheckJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRecheckJob.class);
    static final int BATCH = 20;

    private final SuggestionApprovalCommands approvals;
    private final SuggestionApprovalFlow flow;
    private final com.comedown.shared.time.ServiceTime time;
    private final Duration staleAfter;

    ApprovalRecheckJob(SuggestionApprovalCommands approvals, SuggestionApprovalFlow flow,
            com.comedown.shared.time.ServiceTime time,
            @org.springframework.beans.factory.annotation.Value("${comedown.suggestions.recheck-after:PT2M}")
            Duration staleAfter) {
        this.approvals = approvals;
        this.flow = flow;
        this.time = time;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${comedown.suggestions.recheck-delay:PT1M}",
            initialDelayString = "${comedown.suggestions.recheck-delay:PT1M}")
    void run() {
        recheckStale();
    }

    /** 테스트에서 직접 부를 수 있게 분리했다. */
    public int recheckStale() {
        var stale = approvals.findStaleApproving(time.now().minus(staleAfter), BATCH);
        for (var s : stale) {
            try {
                flow.recheck(s);
            } catch (RuntimeException e) {
                log.warn("승인 재확인 실패(suggestion={}): {}", s.id(), e.getClass().getSimpleName());
            }
        }
        return stale.size();
    }
}
