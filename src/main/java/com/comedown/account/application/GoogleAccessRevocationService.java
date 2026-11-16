package com.comedown.account.application;

import com.comedown.account.contract.GoogleAccessRevocation;
import com.comedown.account.contract.GoogleTokenRevoker;
import com.comedown.account.infrastructure.persistence.GoogleAccountWriter;
import com.comedown.account.infrastructure.persistence.GoogleTokenReader;
import com.comedown.account.infrastructure.persistence.GoogleTokenReader.PendingRevoke;
import com.comedown.account.infrastructure.persistence.GoogleTokenReader.StoredTokens;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.time.ServiceTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구글 토큰 철회. 철회 요청 표시는 트랜잭션에서, 구글 호출은 트랜잭션 밖에서 한다. */
@Service
public class GoogleAccessRevocationService implements GoogleAccessRevocation {

    private static final Logger log = LoggerFactory.getLogger(GoogleAccessRevocationService.class);

    private final GoogleTokenReader tokens;
    private final GoogleAccountWriter writer;
    private final GoogleTokenRevoker google;
    private final ServiceTime time;

    public GoogleAccessRevocationService(GoogleTokenReader tokens, GoogleAccountWriter writer,
            GoogleTokenRevoker google, ServiceTime time) {
        this.tokens = tokens;
        this.writer = writer;
        this.google = google;
        this.time = time;
    }

    @Override
    @Transactional
    public void requestRevoke(long userId) {
        writer.requestRevoke(userId, time.now());
    }

    /**
     * 트랜잭션 밖에서 호출한다(구글 호출을 DB 트랜잭션에 넣지 않는다). 구글 호출 뒤에는 그때도 철회 요청이 남은 행만
     * 지운다.
     */
    @Override
    public boolean revokePending(long userId) {
        PendingRevoke pending = tokens.findPendingRevoke(userId).orElse(null);
        if (pending == null) {
            return true;
        }
        if (!pending.decryptable()) {
            // 복호화할 수 없는 토큰은 구글에 보낼 수 없어 다시 시도해도 철회되지 않는다. 더 철회할 것이 없는 것으로 보고
            // 행을 지워 연결 해제·탈퇴가 끝나게 한다. 토큰·암호문은 로그에 남기지 않는다.
            log.warn("저장된 구글 토큰을 복호화할 수 없어 구글 철회 없이 지웁니다(userId={})", userId);
            writer.deletePendingRevoke(userId);
            return true;
        }
        StoredTokens stored = pending.tokens();
        try {
            google.revoke(stored.refreshToken() != null ? stored.refreshToken() : stored.accessToken());
        } catch (ExternalServiceException e) {
            log.warn("구글 토큰 철회 실패, 다시 시도합니다(userId={}, kind={})", userId, e.kind());
            // 계속 실패하는 행이 재시도 묶음의 앞자리를 차지하지 않게 순서의 맨 뒤로 보낸다.
            writer.markRevokeAttempted(userId, time.now());
            return false;
        }
        // 구글 호출 중에 다시 연결했으면(철회 요청이 풀린 새 토큰) 그 행은 지우지 않는다.
        writer.deletePendingRevoke(userId);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> pendingUsers(int limit) {
        return tokens.usersWithPendingRevoke(limit);
    }
}
