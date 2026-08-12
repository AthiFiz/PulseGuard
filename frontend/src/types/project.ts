export type ProjectRole = 'PROJECT_ADMIN' | 'VIEWER'

export interface ProjectCreator {
  id: number
  email: string
  displayName: string
}

export interface Project {
  id: number
  name: string
  description: string | null
  createdBy: ProjectCreator
  createdAt: string
  updatedAt: string
}

export interface ProjectRequest {
  name: string
  description?: string | null
}

export interface ProjectMember {
  memberId: number
  userId: number
  email: string
  displayName: string
  role: ProjectRole
  joinedAt: string
}

export interface AddProjectMemberRequest {
  email: string
  role: ProjectRole
}

export interface UpdateProjectMemberRequest {
  role: ProjectRole
}
