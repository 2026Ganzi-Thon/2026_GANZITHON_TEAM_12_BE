package com.comedown.account.infrastructure.crypto;

/**
 * 저장된 토큰을 지금 키로 복호화할 수 없다(키가 바뀌었거나 없음, 암호문 손상). 메시지와 원인에는 토큰 내용이 들어가지 않는다.
 * 복호화하지 못한 토큰은 구글에 보낼 수 없으므로 철회 흐름은 이 경우를 "더 철회할 것이 없음"으로 처리한다.
 * {@code @Repository} 빈 밖으로 나가면 Spring이 다른 예외로 바꿔 감싸므로(IllegalStateException 변환) 잡을 곳은
 * 토큰을 읽는 저장소 안이다.
 */
public class TokenDecryptionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public TokenDecryptionException(String message) {
        super(message);
    }

    public TokenDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
