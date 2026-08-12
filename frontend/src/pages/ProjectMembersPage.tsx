import { useState, type FormEvent } from 'react'
import { ApiError } from '../api/apiClient'
import { projectApi } from '../api/projectApi'
import { useAuth } from '../auth/AuthContext'
import { EmptyState } from '../components/States'
import { useProject } from './ProjectLayout'
import { formatDateTime } from '../utils/format'
import type { ProjectRole } from '../types/project'

export function ProjectMembersPage() {
  const { project, members, canManage, reload } = useProject()
  const { user } = useAuth()

  const [email, setEmail] = useState('')
  const [role, setRole] = useState<ProjectRole>('VIEWER')
  const [error, setError] = useState<string | null>(null)
  const [isBusy, setIsBusy] = useState(false)

  async function run(action: () => Promise<unknown>) {
    setError(null)
    setIsBusy(true)
    try {
      await action()
      await reload()
    } catch (caught) {
      // Business rules such as the last-admin protection surface here. The
      // backend's own wording is shown rather than a guess at what it meant.
      setError(caught instanceof ApiError ? caught.message : 'That action failed.')
    } finally {
      setIsBusy(false)
    }
  }

  async function handleAdd(event: FormEvent) {
    event.preventDefault()
    await run(async () => {
      await projectApi.addMember(project.id, { email, role })
      setEmail('')
      setRole('VIEWER')
    })
  }

  return (
    <div>
      <div className="section-header">
        <h2>Members</h2>
      </div>

      {error && (
        <p className="alert alert--error" role="alert">
          {error}
        </p>
      )}

      {canManage && (
        <section className="card card--form">
          <h3>Add a member</h3>
          <p className="field__hint">
            The person must already have a PulseGuard account — adding does not create one.
          </p>
          <form className="inline-form" onSubmit={handleAdd} noValidate>
            <div className="field">
              <label htmlFor="member-email">Email</label>
              <input
                id="member-email"
                type="email"
                required
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />
            </div>
            <div className="field">
              <label htmlFor="member-role">Role</label>
              <select
                id="member-role"
                value={role}
                onChange={(event) => setRole(event.target.value as ProjectRole)}
              >
                <option value="VIEWER">Viewer</option>
                <option value="PROJECT_ADMIN">Project admin</option>
              </select>
            </div>
            <button type="submit" className="btn btn--primary" disabled={isBusy}>
              Add member
            </button>
          </form>
        </section>
      )}

      <section className="card">
        {members.length === 0 ? (
          <EmptyState title="No members" />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">Member</th>
                  <th scope="col">Email</th>
                  <th scope="col">Role</th>
                  <th scope="col">Joined</th>
                  {canManage && <th scope="col">Actions</th>}
                </tr>
              </thead>
              <tbody>
                {members.map((member) => (
                  <tr key={member.memberId}>
                    <td>
                      {member.displayName}
                      {member.userId === user?.id && <span className="tag">You</span>}
                    </td>
                    <td>{member.email}</td>
                    <td>
                      <span className="tag tag--role">
                        {member.role === 'PROJECT_ADMIN' ? 'Project admin' : 'Viewer'}
                      </span>
                    </td>
                    <td>{formatDateTime(member.joinedAt)}</td>
                    {canManage && (
                      <td className="cell--actions">
                        <button
                          type="button"
                          className="btn btn--secondary btn--small"
                          disabled={isBusy}
                          onClick={() =>
                            void run(() =>
                              projectApi.updateMemberRole(project.id, member.memberId, {
                                role:
                                  member.role === 'PROJECT_ADMIN' ? 'VIEWER' : 'PROJECT_ADMIN',
                              }),
                            )
                          }
                        >
                          {member.role === 'PROJECT_ADMIN' ? 'Make viewer' : 'Make admin'}
                        </button>
                        <button
                          type="button"
                          className="btn btn--danger btn--small"
                          disabled={isBusy}
                          onClick={() => {
                            if (
                              globalThis.confirm(
                                `Remove ${member.displayName} from this project?`,
                              )
                            ) {
                              void run(() => projectApi.removeMember(project.id, member.memberId))
                            }
                          }}
                        >
                          Remove
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}
