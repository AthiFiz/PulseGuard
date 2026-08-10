package com.pulseguard.controlapi.security;

import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders Spring Security failures using the same JSON error shape as the rest
 * of the API.
 *
 * <p>Security rejections happen in the filter chain, before any controller or
 * {@code @RestControllerAdvice} runs, so without this an API client would get
 * Spring's default HTML error page or a redirect to a login form.
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /** No or invalid credentials on a protected endpoint. */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        write(
                response,
                request,
                ApiErrorCode.AUTHENTICATION_REQUIRED,
                "Authentication is required to access this resource");
    }

    /** Authenticated, but not allowed to do this. */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        write(
                response,
                request,
                ApiErrorCode.ACCESS_DENIED,
                "You do not have permission to perform this action");
    }

    private void write(
            HttpServletResponse response,
            HttpServletRequest request,
            ApiErrorCode code,
            String message)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(code, message, request.getRequestURI()));
    }
}
