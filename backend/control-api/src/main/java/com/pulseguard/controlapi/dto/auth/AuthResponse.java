package com.pulseguard.controlapi.dto.auth;

/**
 * Successful login result.
 *
 * <p>No refresh token is issued: when the access token expires the client logs
 * in again.
 */
public record AuthResponse(
        String accessToken, String tokenType, long expiresIn, AuthUserSummary user) {

    public static AuthResponse bearer(String accessToken, long expiresInSeconds, AuthUserSummary user) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds, user);
    }
}
