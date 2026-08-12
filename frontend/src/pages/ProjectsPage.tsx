import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/apiClient'
import { projectApi } from '../api/projectApi'
import { EmptyState, ErrorState, LoadingState } from '../components/States'
import { formatDateTime, formatText } from '../utils/format'
import type { Project } from '../types/project'

export function ProjectsPage() {
  const [projects, setProjects] = useState<Project[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [isCreating, setIsCreating] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  const load = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      setProjects(await projectApi.list())
    } catch (caught) {
      setError(
        caught instanceof ApiError ? caught.message : 'Unable to load your projects.',
      )
    } finally {
      setIsLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function handleCreate(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    setIsSubmitting(true)
    try {
      await projectApi.create({ name, description: description || null })
      setName('')
      setDescription('')
      setIsCreating(false)
      await load()
    } catch (caught) {
      setFormError(caught instanceof ApiError ? caught.message : 'Unable to create the project.')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="page">
      <div className="page__header">
        <div>
          <h1>Projects</h1>
          <p className="page__subtitle">
            Every project you belong to. Monitors and members live inside a project.
          </p>
        </div>
        {!isCreating && (
          <button type="button" className="btn btn--primary" onClick={() => setIsCreating(true)}>
            New project
          </button>
        )}
      </div>

      {isCreating && (
        <section className="card card--form">
          <h2>New project</h2>
          {formError && (
            <p className="alert alert--error" role="alert">
              {formError}
            </p>
          )}
          <form onSubmit={handleCreate} noValidate>
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
                rows={2}
                maxLength={1000}
                value={description}
                onChange={(event) => setDescription(event.target.value)}
              />
            </div>
            <div className="form-actions">
              <button type="submit" className="btn btn--primary" disabled={isSubmitting}>
                {isSubmitting ? 'Creating…' : 'Create project'}
              </button>
              <button
                type="button"
                className="btn btn--ghost"
                onClick={() => {
                  setIsCreating(false)
                  setFormError(null)
                }}
              >
                Cancel
              </button>
            </div>
          </form>
        </section>
      )}

      {isLoading && <LoadingState message="Loading projects…" />}

      {!isLoading && error && <ErrorState message={error} onRetry={() => void load()} />}

      {!isLoading && !error && projects.length === 0 && (
        <EmptyState
          title="No projects yet"
          description="Create a project to start monitoring your APIs."
          action={
            <button type="button" className="btn btn--primary" onClick={() => setIsCreating(true)}>
              Create your first project
            </button>
          }
        />
      )}

      {!isLoading && !error && projects.length > 0 && (
        <ul className="project-grid">
          {projects.map((project) => (
            <li key={project.id}>
              <Link to={`/projects/${project.id}/dashboard`} className="card card--link">
                <h2>{project.name}</h2>
                <p className="card__body">{formatText(project.description)}</p>
                <p className="card__meta">Created {formatDateTime(project.createdAt)}</p>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
