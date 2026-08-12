import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/apiClient'
import { projectApi } from '../api/projectApi'
import { useProject } from './ProjectLayout'

export function ProjectSettingsPage() {
  const { project, canManage, reload } = useProject()
  const navigate = useNavigate()

  const [name, setName] = useState(project.name)
  const [description, setDescription] = useState(project.description ?? '')
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isBusy, setIsBusy] = useState(false)

  // The tab is hidden from a viewer, but a typed URL should not render a form
  // whose every submission would be refused.
  if (!canManage) {
    return (
      <p className="alert alert--error" role="alert">
        You do not have permission to change this project.
      </p>
    )
  }

  async function handleSave(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setMessage(null)
    setIsBusy(true)
    try {
      await projectApi.update(project.id, { name, description: description || null })
      await reload()
      setMessage('Project updated.')
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Unable to update the project.')
    } finally {
      setIsBusy(false)
    }
  }

  async function handleDelete() {
    if (
      !globalThis.confirm(
        `Delete "${project.name}"? Its monitors and check history will be removed too.`,
      )
    ) {
      return
    }
    setError(null)
    setIsBusy(true)
    try {
      await projectApi.remove(project.id)
      navigate('/projects', { replace: true })
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Unable to delete the project.')
      setIsBusy(false)
    }
  }

  return (
    <div>
      <div className="section-header">
        <h2>Settings</h2>
      </div>

      {message && (
        <p className="alert alert--success" role="status">
          {message}
        </p>
      )}
      {error && (
        <p className="alert alert--error" role="alert">
          {error}
        </p>
      )}

      <section className="card card--form">
        <h3>Project details</h3>
        <form onSubmit={handleSave} noValidate>
          <div className="field">
            <label htmlFor="project-name">Name</label>
            <input
              id="project-name"
              type="text"
              required
              minLength={2}
              maxLength={150}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="project-description">Description</label>
            <textarea
              id="project-description"
              rows={3}
              maxLength={1000}
              value={description}
              onChange={(event) => setDescription(event.target.value)}
            />
          </div>
          <div className="form-actions">
            <button type="submit" className="btn btn--primary" disabled={isBusy}>
              {isBusy ? 'Saving…' : 'Save changes'}
            </button>
          </div>
        </form>
      </section>

      <section className="card card--danger">
        <h3>Delete project</h3>
        <p>
          Deleting removes the project, its monitors and all recorded check history. This cannot be
          undone.
        </p>
        <button type="button" className="btn btn--danger" disabled={isBusy} onClick={() => void handleDelete()}>
          Delete project
        </button>
      </section>
    </div>
  )
}
