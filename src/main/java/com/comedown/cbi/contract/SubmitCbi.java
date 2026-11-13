package com.comedown.cbi.contract;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * CBI 제출 명령. 형식 검증(13개 고유 문항·보기 0~4·버전)은 호출하는 쪽이 끝낸 뒤 보낸다.
 *
 * @param round   INITIAL·DAY28
 * @param options 문항 번호(1~13) → 원래 선택(0~4)
 * @param takenAt 응답 시각. null이면 저장하는 순간(현재 시각). 과거 시각은 local 시연 데이터(dev seed)만 넘긴다
 */
public record SubmitCbi(long userId, String round, Map<Integer, Integer> options, Instant takenAt) {

    public SubmitCbi {
        Objects.requireNonNull(round, "round");
        options = Map.copyOf(options);
    }

    /** 현재 시각에 응답한 제출. */
    public SubmitCbi(long userId, String round, Map<Integer, Integer> options) {
        this(userId, round, options, null);
    }
}
