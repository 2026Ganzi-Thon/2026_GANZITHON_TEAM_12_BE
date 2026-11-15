package com.comedown.account.application;

import com.comedown.account.infrastructure.persistence.GoogleAccountWriter;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Google 로그인(가입 포함)과 토큰 저장 유스케이스. 외부 호출 없이 DB만 쓴다. */
@Service
public class GoogleAccountService {

    /** 로그인한 사용자. {@code deleting}이면 탈퇴 진행 중이라 세션을 만들지 않는다. */
    public record GoogleLogin(long userId, boolean deleting) {
    }

    /** 구글이 발급한 토큰과 실제 허용 범위. */
    public record TokenGrant(String accessToken, String refreshToken, Instant issuedAt, Instant expiresAt,
            Set<String> scopes) {
    }

    private final GoogleAccountWriter writer;
    private final ServiceTime time;

    public GoogleAccountService(GoogleAccountWriter writer, ServiceTime time) {
        this.writer = writer;
        this.time = time;
    }

    @Transactional
    public GoogleLogin loginOrRegister(String googleSub, String email) {
        GoogleAccountWriter.LoginUser user = writer.loginOrRegister(googleSub, email, time.now());
        return new GoogleLogin(user.id(), user.deleting());
    }

    @Transactional
    public void saveTokens(long userId, String googleSub, TokenGrant grant) {
        writer.saveTokens(userId, googleSub, grant.accessToken(), grant.refreshToken(), grant.issuedAt(),
                grant.expiresAt(), String.join(" ", grant.scopes()), time.now());
    }
}
