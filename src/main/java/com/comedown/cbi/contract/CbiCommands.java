package com.comedown.cbi.contract;

/** cbi가 공개하는 CBI 제출 계약(cbi-ko-2026.11.2). */
public interface CbiCommands {

    /**
     * 채점해 응답 1회와 13개 답을 한 트랜잭션으로 저장한다. 호출하는 쪽의 트랜잭션에 참여한다.
     *
     * @throws com.comedown.shared.error.ApiException 같은 회차를 이미 응답했으면 409 CBI_ALREADY_TAKEN
     */
    CbiResult submit(SubmitCbi command);
}
