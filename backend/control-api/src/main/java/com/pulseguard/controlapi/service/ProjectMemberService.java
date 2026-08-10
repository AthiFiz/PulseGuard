package com.pulseguard.controlapi.service;

import com.pulseguard.controlapi.dto.member.AddProjectMemberRequest;
import com.pulseguard.controlapi.dto.member.ProjectMemberResponse;
import com.pulseguard.controlapi.dto.member.UpdateProjectMemberRequest;
import java.util.List;

public interface ProjectMemberService {

    List<ProjectMemberResponse> listMembers(Long projectId);

    ProjectMemberResponse addMember(Long projectId, AddProjectMemberRequest request);

    ProjectMemberResponse updateMemberRole(Long projectId, Long memberId, UpdateProjectMemberRequest request);

    void removeMember(Long projectId, Long memberId);
}
