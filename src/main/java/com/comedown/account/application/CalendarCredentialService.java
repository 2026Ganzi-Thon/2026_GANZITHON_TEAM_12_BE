package com.comedown.account.application;

import com.comedown.account.contract.CalendarCredential;
import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.account.contract.GoogleTokenRefresher;
import com.comedown.account.contract.GoogleTokenRefresher.RefreshedToken;
import com.comedown.account.infrastructure.persistence.GoogleAccountWriter;
import com.comedown.account.infrastructure.persistence.GoogleTokenReader;
import com.comedown.account.infrastructure.persistence.GoogleTokenReader.ActiveTokens;
import com.comedown.account.infrastructure.persistence.GoogleTokenReader.StoredTokens;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.time.ServiceTime;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 캘린더 호출용 access token을 내어 준다. 만료됐으면 refresh token으로 갱신해 저장한다.
 * 구글 호출이 있어 트랜잭션으로 감싸지 않는다(DB 읽기·쓰기는 각각 짧게 끝난다).
 * <p>
 * 저장된 토큰을 지금 키로 복호화할 수 없으면(키 변경·암호문 손상) 500 대신 {@code CALENDAR_REAUTH_REQUIRED}로
 * 다시 연결을 안내한다. 다시 연결하면 토큰이 지금 키로 새로 저장된다.
 */
@Service
public class CalendarCredentialService implements CalendarCredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(CalendarCredentialService.class);
    /** 이 시간 안에 만료되면 미리 갱신한다. */
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final GoogleTokenReader tokens;
    private final GoogleAccountWriter writer;
    private final GoogleTokenRefresher google;
    private final ServiceTime time;

    public CalendarCredentialService(GoogleTokenReader tokens, GoogleAccountWriter writer, GoogleTokenRefresher google,
            ServiceTime time) {
        this.tokens = tokens;
        this.writer = writer;
        this.google = google;
        this.time = time;
    }

    @Override
    public CalendarCredential credentialFor(long userId) {
        StoredTokens stored = storedTokens(userId);
        if (!stored.hasCalendarScope()) {
            throw new ApiException(ErrorCode.CALENDAR_SCOPE_MISSING);
        }
        Instant now = time.now();
        if (stored.accessExpiresAt() != null && stored.accessExpiresAt().isAfter(now.plus(REFRESH_MARGIN))) {
            return new CalendarCredential(stored.accessToken());
        }
        if (stored.refreshToken() == null) {
            throw new ApiException(ErrorCode.CALENDAR_REAUTH_REQUIRED);
        }
        try {
            RefreshedToken refreshed = google.refresh(stored.refreshToken(), now);
            writer.updateAccessToken(userId, refreshed.accessToken(), refreshed.issuedAt(), refreshed.expiresAt(), now);
            return new CalendarCredential(refreshed.accessToken());
        } catch (ExternalServiceException e) {
            log.warn("캘린더 토큰 갱신 실패(userId={}, kind={})", userId, e.kind());
            throw new ApiException(e.kind() == ExternalServiceException.Kind.FAILURE
                    ? ErrorCode.CALENDAR_API_ERROR : ErrorCode.CALENDAR_REAUTH_REQUIRED);
        }
    }

    private StoredTokens storedTokens(long userId) {
        ActiveTokens active = tokens.find(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.CALENDAR_NOT_CONNECTED));
        if (!active.decryptable()) {
            log.warn("캘린더 토큰을 복호화할 수 없어 다시 연결을 안내합니다(userId={})", userId);
            throw new ApiException(ErrorCode.CALENDAR_REAUTH_REQUIRED);
        }
        return active.tokens();
    }
}
