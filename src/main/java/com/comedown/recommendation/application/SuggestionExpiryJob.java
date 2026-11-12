package com.comedown.recommendation.application;

import com.comedown.recommendation.contract.SuggestionCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 만료 시각이 지난 PROPOSED 제안을 주기적으로 EXPIRED로 바꾼다. */
@Component
class SuggestionExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(SuggestionExpiryJob.class);

    private final SuggestionCommands suggestions;

    SuggestionExpiryJob(SuggestionCommands suggestions) {
        this.suggestions = suggestions;
    }

    @Scheduled(cron = "${comedown.suggestions.expiry-cron:0 */5 * * * *}", zone = "Asia/Seoul")
    void expire() {
        int expired = suggestions.expireDue();
        if (expired > 0) {
            log.info("Suggestions expired: {}", expired);
        }
    }
}
