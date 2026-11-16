package com.comedown.experience.suggestions.application;

import com.comedown.recommendation.contract.SuggestionApprovalCommands;
import com.comedown.recommendation.contract.SuggestionView;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 외부 결과를 확인하지 못한 채 승인 중(APPROVING)으로 남은 제안을 주기적으로 같은 등록 키로 다시 확인·확정한다.
 * (같은 앱 안의 작업, 메시지 브로커 없음.)
 * <p>
 * 한 번에 오래된 순으로 {@link #BATCH}개만 본다. 재확인하고도 APPROVING으로 남은 제안은 순서를 맨 뒤로 미뤄서
 * (갱신 시각 변경) {@code recheck-after}가 다시 지난 뒤에 재확인한다. 풀리지 않는 제안이 매번 앞자리를 차지해 새 제안의
 * 재확인을 막지 않게 하려는 것이다.
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
            defer(s);
        }
        return stale.size();
    }

    /** 아직 APPROVING이면 재확인 순서를 뒤로 미룬다(확정된 제안은 바뀌지 않는다). */
    private void defer(SuggestionView s) {
        try {
            approvals.deferRecheck(s.userId(), s.id());
        } catch (RuntimeException e) {
            log.warn("승인 재확인 미루기 실패(suggestion={}): {}", s.id(), e.getClass().getSimpleName());
        }
    }
}
