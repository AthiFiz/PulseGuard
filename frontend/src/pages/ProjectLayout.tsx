import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { NavLink, Outlet, useParams } from 'react-router-dom'
import { projectApi } from '../api/projectApi'
import { ApiError } from '../api/apiClient'
import { useAuth } from '../auth/AuthContext'
import { canManageProject, findOwnMembership } from '../auth/permissions'
import { ErrorState, LoadingState } from '../components/States'
import type { Project, ProjectMember } from '../types/project'

interface ProjectContextValue {
  project: Project
  members: ProjectMember[]
  /** Whether the UI should offer management controls. Never a security check. */
  canManage: boolean
  reload: () => Promise<void>
}

const ProjectContext = createContext<ProjectContextValue | null>(null)

export function useProject(): ProjectContextValue {
  const context = useContext(ProjectContext)
  if (!context) {
    throw new Error('useProject must be used inside a project route')
  }
  return context
}

/**
 * Loads a project once for all of its pages and works out what the current user
 * may do with it.
 *
 * The project role is not in the JWT — deliberately, since membership can be
 * revoked without a new login. So it is resolved by matching the signed-in user
 * against the project's member list, which any member may read.
 */
export function ProjectLayout() {
  const { projectId } = useParams()
  const { user } = useAuth()
  const [project, setProject] = useState<Project | null>(null)
  const [members, setMembers] = useState<ProjectMember[]>([])
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  const id = Number(projectId)

  const load = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      // A system administrator can read a project without belonging to it, so
      // the two calls are independent rather than nested.
      const [loadedProject, loadedMembers] = await Promise.all([
        projectApi.get(id),
        projectApi.listMembers(id),
      ])
      setProject(loadedProject)
      setMembers(loadedMembers)
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : 'Unable to load this project. Check your connection and try again.',
      )
    } finally {
      setIsLoading(false)
    }
  }, [id])

  useEffect(() => {
    if (Number.isNaN(id)) {
      setError('That project reference is not valid.')
      setIsLoading(false)
      return
    }
    void load()
  }, [id, load])

  if (isLoading) {
    return <LoadingState message="Loading project…" />
  }

  if (error || !project) {
    return <ErrorState message={error ?? 'Project not found.'} onRetry={() => void load()} />
  }

  const canManage = canManageProject(user, findOwnMembership(user, members))

  return (
    <ProjectContext.Provider value={{ project, members, canManage, reload: load }}>
      <div className="project">
        <div className="project__header">
          <div>
            <h1>{project.name}</h1>
            {project.description && <p className="project__description">{project.description}</p>}
          </div>
          {!canManage && <span className="tag">Read only</span>}
        </div>

        <nav className="tabs" aria-label="Project sections">
          <NavLink to={`/projects/${project.id}/dashboard`} className={tabClass}>
            Dashboard
          </NavLink>
          <NavLink to={`/projects/${project.id}/monitors`} className={tabClass} end>
            Monitors
          </NavLink>
          {/* Visible to every member, viewers included: incidents are read-only
              for everyone, so there is no role to check here. */}
          <NavLink to={`/projects/${project.id}/incidents`} className={tabClass}>
            Incidents
          </NavLink>
          <NavLink to={`/projects/${project.id}/members`} className={tabClass}>
            Members
          </NavLink>
          {canManage && (
            <NavLink to={`/projects/${project.id}/settings`} className={tabClass}>
              Settings
            </NavLink>
          )}
        </nav>

        <Outlet />
      </div>
    </ProjectContext.Provider>
  )
}

function tabClass({ isActive }: { isActive: boolean }): string {
  return isActive ? 'tab tab--active' : 'tab'
}
