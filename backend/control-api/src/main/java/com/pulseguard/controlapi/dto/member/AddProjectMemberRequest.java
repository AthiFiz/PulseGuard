package com.pulseguard.controlapi.dto.member;

import com.pulseguard.controlapi.enums.ProjectRole;
import com.pulseguard.controlapi.util.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Adds an already-registered user to a project. Users are never created here. */
public record AddProjectMemberRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email,

        @NotNull(message = "Role is required") ProjectRole role) {

    /** Same normalization as registration, so the lookup always matches. */
    public AddProjectMemberRequest {
        email = EmailNormalizer.normalize(email);
    }
}
