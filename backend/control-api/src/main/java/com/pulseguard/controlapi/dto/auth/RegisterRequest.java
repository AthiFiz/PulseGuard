package com.pulseguard.controlapi.dto.auth;

import com.pulseguard.controlapi.util.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public registration payload.
 *
 * <p>There is deliberately no {@code systemRole} field: public registration
 * must never be able to create an administrator.
 */
public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
        String password,

        @NotBlank(message = "Display name is required")
        @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
        String displayName) {

    /**
     * Normalizes before validation runs. Jackson builds the record first, so
     * {@code @Email} and {@code @Size} then see the cleaned values rather than
     * rejecting an address purely for surrounding whitespace.
     */
    public RegisterRequest {
        email = EmailNormalizer.normalize(email);
        displayName = displayName == null ? null : displayName.trim();
    }
}
