package com.pulseguard.controlapi.controller;

import com.pulseguard.controlapi.dto.member.AddProjectMemberRequest;
import com.pulseguard.controlapi.dto.member.ProjectMemberResponse;
import com.pulseguard.controlapi.dto.member.UpdateProjectMemberRequest;
import com.pulseguard.controlapi.service.ProjectMemberService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
@RequiredArgsConstructor
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;

    @GetMapping
    public List<ProjectMemberResponse> listMembers(@PathVariable Long projectId) {
        return projectMemberService.listMembers(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectMemberResponse addMember(@PathVariable Long projectId,
                                           @Valid @RequestBody AddProjectMemberRequest request) {
        return projectMemberService.addMember(projectId, request);
    }

    @PutMapping("/{memberId}")
    public ProjectMemberResponse updateMemberRole(
            @PathVariable Long projectId,
            @PathVariable Long memberId,
            @Valid @RequestBody UpdateProjectMemberRequest request) {
        return projectMemberService.updateMemberRole(projectId, memberId, request);
    }

    @DeleteMapping("/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable Long projectId, @PathVariable Long memberId) {
        projectMemberService.removeMember(projectId, memberId);
    }
}
