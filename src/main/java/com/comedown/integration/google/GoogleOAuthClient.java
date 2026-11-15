package com.comedown.integration.google;

import com.comedown.account.contract.GoogleTokenRefresher;
import com.comedown.account.contract.GoogleTokenRevoker;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Google OAuth 토큰 엔드포인트 호출(access token 갱신). account의 {@link GoogleTokenRefresher} 포트를 구현한다. */
@Component
public class GoogleOAuthClient implements GoogleTokenRefresher, GoogleTokenRevoker {

    private final RestClient client;
    private final String clientId;
    private final String clientSecret;
    private final String revokeUrl;

    @Autowired
    public GoogleOAuthClient(
            @Value("${comedown.google.token-url:https://oauth2.googleapis.com/token}") String tokenUrl,
            @Value("${comedown.google.client-id:}") String clientId,
            @Value("${comedown.google.client-secret:}") String clientSecret,
            @Value("${comedown.google.revoke-url:https://oauth2.googleapis.com/revoke}") String revokeUrl) {
        this(GoogleHttp.newBuilder(), tokenUrl, clientId, clientSecret, revokeUrl);
    }

    /** 테스트에서 가짜 서버를 연결한 빌더를 넣을 때 쓴다. */
    GoogleOAuthClient(RestClient.Builder builder, String tokenUrl, String clientId, String clientSecret,
            String revokeUrl) {
        this.revokeUrl = revokeUrl;
        this.client = builder.baseUrl(tokenUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public RefreshedToken refresh(String refreshToken, Instant now) {
        if (clientId.isBlank()) {
            throw new ExternalServiceException(Kind.FAILURE, "Google 클라이언트가 설정되지 않았습니다.");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        try {
            Map<String, Object> body = client.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            Object access = body == null ? null : body.get("access_token");
            if (!(access instanceof String token) || token.isBlank()) {
                throw new ExternalServiceException(Kind.FAILURE, "토큰 갱신 응답에 access token이 없습니다.");
            }
            long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 3600;
            return new RefreshedToken(token, now, now.plusSeconds(expiresIn));
        } catch (RestClientResponseException e) {
            // invalid_grant(철회·만료)는 다시 연결해야 한다. 응답 본문은 로그에 남기지 않는다.
            boolean invalid = e.getStatusCode().value() == 400 || e.getStatusCode().value() == 401;
            throw new ExternalServiceException(invalid ? Kind.UNAUTHORIZED : Kind.FAILURE,
                    "토큰 갱신에 실패했습니다(status=" + e.getStatusCode().value() + ").");
        } catch (RestClientException e) {
            throw new ExternalServiceException(Kind.FAILURE, "토큰 갱신 요청에 실패했습니다.", e);
        }
    }

    /** 토큰을 철회한다. 이미 철회·만료된 토큰(400)은 끝난 것으로 본다. 응답 본문·토큰은 로그에 남기지 않는다. */
    @Override
    public void revoke(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        try {
            client.post().uri(revokeUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400) {
                return;
            }
            throw new ExternalServiceException(Kind.FAILURE,
                    "토큰 철회에 실패했습니다(status=" + e.getStatusCode().value() + ").");
        } catch (RestClientException e) {
            throw new ExternalServiceException(Kind.FAILURE, "토큰 철회 요청에 실패했습니다.", e);
        }
    }
}
