import { apiClient } from './apiClient'
import type {
  AddProjectMemberRequest,
  Project,
  ProjectMember,
  ProjectRequest,
  UpdateProjectMemberRequest,
} from '../types/project'

export const projectApi = {
  list: () => apiClient.get<Project[]>('/api/v1/projects'),

  get: (projectId: number) => apiClient.get<Project>(`/api/v1/projects/${projectId}`),

  create: (request: ProjectRequest) => apiClient.post<Project>('/api/v1/projects', request),

  update: (projectId: number, request: ProjectRequest) =>
    apiClient.put<Project>(`/api/v1/projects/${projectId}`, request),

  remove: (projectId: number) => apiClient.delete<void>(`/api/v1/projects/${projectId}`),

  listMembers: (projectId: number) =>
    apiClient.get<ProjectMember[]>(`/api/v1/projects/${projectId}/members`),

  addMember: (projectId: number, request: AddProjectMemberRequest) =>
    apiClient.post<ProjectMember>(`/api/v1/projects/${projectId}/members`, request),

  updateMemberRole: (projectId: number, memberId: number, request: UpdateProjectMemberRequest) =>
    apiClient.put<ProjectMember>(`/api/v1/projects/${projectId}/members/${memberId}`, request),

  removeMember: (projectId: number, memberId: number) =>
    apiClient.delete<void>(`/api/v1/projects/${projectId}/members/${memberId}`),
}
