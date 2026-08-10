package com.pulseguard.controlapi.dto.member;

import com.pulseguard.controlapi.domain.ProjectMember;
import com.pulseguard.controlapi.enums.ProjectRole;
import java.time.Instant;

/** Membership as exposed by the API. Carries no password-related data. */
public record ProjectMemberResponse(
        Long memberId,
        Long userId,
        String email,
        String displayName,
        ProjectRole role,
        Instant joinedAt) {

    public static ProjectMemberResponse from(ProjectMember member) {
        return new ProjectMemberResponse(
                member.getId(),
                member.getUser().getId(),
                member.getUser().getEmail(),
                member.getUser().getDisplayName(),
                member.getRole(),
                member.getCreatedAt());
    }
}
