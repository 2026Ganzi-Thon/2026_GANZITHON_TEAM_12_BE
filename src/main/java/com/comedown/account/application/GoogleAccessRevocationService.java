package com.comedown.account.application;

import com.comedown.account.contract.GoogleAccessRevocation;
import com.comedown.account.contract.GoogleTokenRevoker;
import com.comedown.account.infrastructure.persistence.GoogleAccountWriter;
import com.comedown.account.infrastructure.persistence.GoogleTokenReader;
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

    /** 트랜잭션 밖에서 호출한다(구글 호출을 DB 트랜잭션에 넣지 않는다). */
    @Override
    public boolean revokePending(long userId) {
        StoredTokens pending = tokens.findPendingRevoke(userId).orElse(null);
        if (pending == null) {
            return true;
        }
        try {
            google.revoke(pending.refreshToken() != null ? pending.refreshToken() : pending.accessToken());
        } catch (ExternalServiceException e) {
            log.warn("구글 토큰 철회 실패, 다시 시도합니다(userId={}, kind={})", userId, e.kind());
            return false;
        }
        writer.deleteTokens(userId);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> pendingUsers(int limit) {
        return tokens.usersWithPendingRevoke(limit);
    }
}
