package com.comedown.account.application;

import com.comedown.account.infrastructure.persistence.AccountStore;
import com.comedown.account.infrastructure.persistence.GoogleAccountWriter;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    private final AccountStore store;
    private final ServiceTime time;

    public GoogleAccountService(GoogleAccountWriter writer, AccountStore store, ServiceTime time) {
        this.writer = writer;
        this.store = store;
        this.time = time;
    }

    /**
     * READ COMMITTED로 실행한다: 같은 구글 계정의 첫 로그인이 겹쳐 INSERT가 중복 키로 실패했을 때, 먼저 커밋된 행을
     * 같은 트랜잭션에서 다시 읽어 그 사용자로 로그인시키기 위해서다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GoogleLogin loginOrRegister(String googleSub, String email) {
        GoogleAccountWriter.LoginUser user = writer.loginOrRegister(googleSub, email, time.now());
        return new GoogleLogin(user.id(), user.deleting());
    }

    /** 이 구글 계정(sub)이 해당 사용자의 계정인지. 로그인한 채 캘린더를 연결할 때 다른 계정을 고르지 않았는지 확인한다. */
    @Transactional(readOnly = true)
    public boolean isGoogleAccountOf(long userId, String googleSub) {
        return store.hasGoogleSub(userId, googleSub);
    }

    /**
     * 일반 로그인에서 받은 토큰 저장. 철회 요청이 남아 있으면(연결 해제·탈퇴의 구글 철회가 아직 안 끝남) 저장된 토큰과
     * 철회 요청을 그대로 둔다.
     */
    @Transactional
    public void saveLoginTokens(long userId, String googleSub, TokenGrant grant) {
        save(userId, googleSub, grant, false);
    }

    /** 캘린더 연결 허용에서 받은 토큰 저장. 사용자가 다시 허용한 것이므로 남아 있던 철회 요청을 푼다. */
    @Transactional
    public void saveCalendarTokens(long userId, String googleSub, TokenGrant grant) {
        save(userId, googleSub, grant, true);
    }

    private void save(long userId, String googleSub, TokenGrant grant, boolean calendarConnection) {
        writer.saveTokens(userId, googleSub, grant.accessToken(), grant.refreshToken(), grant.issuedAt(),
                grant.expiresAt(), String.join(" ", grant.scopes()), calendarConnection, time.now());
    }
}
