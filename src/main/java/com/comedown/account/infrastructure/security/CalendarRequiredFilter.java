package com.comedown.account.infrastructure.security;

import com.comedown.account.contract.CalendarConnectionQuery;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ErrorResponseWriter;
import com.comedown.shared.security.ComeDownPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 구글 캘린더 권한이 없으면 서비스를 쓸 수 없다(2026-11-19 결정). 로그인 성공 처리({@link GoogleOAuthSuccessHandler})는
 * 캘린더 없는 세션을 만들지 않지만, 그 결정 전에 만들어진 세션이나 로그인 뒤 캘린더 연결을 해제한 세션이 남을 수 있다.
 * 그래서 로그인한 {@code /api/**} 요청마다 캘린더 연결을 확인하고, 연결돼 있지 않으면 세션을 끊고
 * 401 {@code UNAUTHENTICATED}로 답한다(가입 정보·닉네임 저장을 포함한 모든 단계가 막힌다). 프론트는 지금처럼 401이면
 * 시작 화면으로 가고, 다시 로그인하면 캘린더 권한 화면을 거친다.
 * <p>
 * 그대로 통과: 로그인하지 않은 요청(뒤의 권한 규칙이 401로 처리), CSRF 쿠키 받기, 로그아웃, 개발용 경로(local).
 */
class CalendarRequiredFilter extends OncePerRequestFilter {

    static final String MESSAGE = "구글 캘린더 권한이 필요해요. 다시 로그인해 주세요.";

    private final CalendarConnectionQuery calendar;
    private final ErrorResponseWriter errors;
    private final SecurityContextLogoutHandler endSession = new SecurityContextLogoutHandler();

    CalendarRequiredFilter(CalendarConnectionQuery calendar, ErrorResponseWriter errors) {
        this.calendar = calendar;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/")
                || path.equals("/api/auth/csrf")
                || path.equals("/api/auth/logout")
                || path.startsWith("/api/dev/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ComeDownPrincipal principal
                && !calendar.isConnected(principal.userId())) {
            endSession.logout(request, response, authentication);
            errors.write(response, ErrorCode.UNAUTHENTICATED, MESSAGE);
            return;
        }
        chain.doFilter(request, response);
    }
}
