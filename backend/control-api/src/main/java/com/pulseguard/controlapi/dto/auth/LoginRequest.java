package com.pulseguard.controlapi.dto.auth;

import com.pulseguard.controlapi.util.EmailNormalizer;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Email is required")
        String email,

        @NotBlank(message = "Password is required")
        String password) {

    public LoginRequest {
        email = EmailNormalizer.normalize(email);
    }

    /** Guards against the password reaching a log through an accidental toString(). */
    @Override
    public String toString() {
        return "LoginRequest[email=%s, password=***]".formatted(email);
    }
}
