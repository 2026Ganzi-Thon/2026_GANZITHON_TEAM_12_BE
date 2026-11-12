package com.comedown.shared.security;

import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentUser} 파라미터를 SecurityContext의 {@link ComeDownPrincipal}에서 채운다.
 * 로그인하지 않았거나 principal이 서비스 사용자가 아니면 401 UNAUTHENTICATED.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public CurrentUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof ComeDownPrincipal principal)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return new CurrentUser(principal.userId());
    }
}
