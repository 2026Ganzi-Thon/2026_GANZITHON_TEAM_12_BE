package com.comedown.cbi.contract;

import java.util.Map;
import java.util.Objects;

/**
 * CBI 제출 명령. 형식 검증(13개 고유 문항·보기 0~4·버전)은 호출하는 쪽이 끝낸 뒤 보낸다.
 *
 * @param round   INITIAL·DAY28
 * @param options 문항 번호(1~13) → 원래 선택(0~4)
 */
public record SubmitCbi(long userId, String round, Map<Integer, Integer> options) {

    public SubmitCbi {
        Objects.requireNonNull(round, "round");
        options = Map.copyOf(options);
    }
}
