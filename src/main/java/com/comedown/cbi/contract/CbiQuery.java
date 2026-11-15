package com.comedown.cbi.contract;

import java.util.List;
import java.util.Optional;

/** cbi가 공개하는 CBI 결과 읽기 계약. */
public interface CbiQuery {

    /** 응답 이력(최신순). */
    List<CbiResult> findHistory(long userId);

    /** 회차별 결과(INITIAL·DAY28). */
    Optional<CbiResult> findRound(long userId, String round);
}
