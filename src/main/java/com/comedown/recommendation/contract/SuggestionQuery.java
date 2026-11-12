package com.comedown.recommendation.contract;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** recommendation이 공개하는 제안 읽기 계약. */
public interface SuggestionQuery {

    /**
     * 조건에 맞는 제안(시작 시각 순). null인 조건은 거르지 않는다.
     *
     * @param type   PRE_START·RECOVERY
     * @param status PROPOSED 등
     */
    List<SuggestionView> search(long userId, String type, String status, LocalDate targetDate);

    Optional<SuggestionView> find(long userId, long suggestionId);
}
