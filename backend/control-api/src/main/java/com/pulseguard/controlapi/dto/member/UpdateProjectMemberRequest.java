package com.pulseguard.controlapi.dto.member;

import com.pulseguard.controlapi.enums.ProjectRole;
import jakarta.validation.constraints.NotNull;

public record UpdateProjectMemberRequest(@NotNull(message = "Role is required") ProjectRole role) {
}
