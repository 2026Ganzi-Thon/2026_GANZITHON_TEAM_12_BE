package com.comedown.shared.security.dev;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/dev/login} → 204. local 프로필 전용 테스트 로그인.
 * Google 로그인 없이 지정한 users.id로 세션을 만든다. 운영 프로필에서는 빈이 만들어지지 않는다.
 * <p>
 * 사용자 행은 만들지 않는다(users 쓰기 소유자는 account). 해당 id의 사용자가 DB에 있어야 FK가 걸린 저장이 성공한다.
 * 로그인 뒤에는 {@code GET /api/auth/csrf}로 CSRF 쿠키를 다시 받는다.
 */
@RestController
@Profile("local")
public class DevLoginController {

    private static final Logger log = LoggerFactory.getLogger(DevLoginController.class);

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    record DevLoginRequest(@NotNull @Positive Long userId) {
    }

    @PostMapping("/api/dev/login")
    public ResponseEntity<Void> login(@Valid @RequestBody DevLoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        // 세션 고정 공격 방지: 기존 세션이 있으면 id를 바꾸고, 없으면 새로 만든다.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                new DevPrincipal(body.userId()), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        log.info("Local dev login: userId={}", body.userId());
        return ResponseEntity.noContent().build();
    }
}
