package com.pulseguard.controlapi.security;

/** Names of the custom claims PulseGuard puts in its access tokens. */
public final class TokenClaims {

    public static final String EMAIL = "email";
    public static final String SYSTEM_ROLE = "system_role";

    private TokenClaims() {
    }
}
