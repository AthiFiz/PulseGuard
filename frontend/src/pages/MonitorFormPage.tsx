import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/apiClient'
import { monitorApi } from '../api/monitorApi'
import { projectApi } from '../api/projectApi'
import { useAuth } from '../auth/AuthContext'
import { canManageMonitors, findOwnMembership } from '../auth/permissions'
import { ErrorState, LoadingState } from '../components/States'
import type { MonitorRequest } from '../types/monitor'

const DEFAULTS: MonitorRequest = {
  name: '',
  description: '',
  url: '',
  httpMethod: 'GET',
  expectedStatusCode: 200,
  intervalSeconds: 60,
  timeoutSeconds: 5,
  failureThreshold: 3,
}

/**
 * Creating and editing a monitor use the same form, because the backend accepts
 * the same fields for both.
 *
 * Operational fields — status, consecutive failures, last and next check — are
 * absent by design. They belong to the monitoring engine, and the API will not
 * accept them from a client.
 */
export function MonitorFormPage({ mode }: { mode: 'create' | 'edit' }) {
  const navigate = useNavigate()
  const params = useParams()
  const { user } = useAuth()
  const projectId = Number(params.projectId)
  const monitorId = Number(params.monitorId)

  const [form, setForm] = useState<MonitorRequest>(DEFAULTS)
  const [isLoading, setIsLoading] = useState(true)
  const [canManage, setCanManage] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  useEffect(() => {
    let active = true

    async function load() {
      try {
        // The form lives outside the project layout, so the owning project is
        // taken from the route when creating and from the monitor when editing.
        let owningProjectId = projectId

        if (mode === 'edit') {
          const monitor = await monitorApi.get(monitorId)
          if (!active) {
            return
          }
          owningProjectId = monitor.projectId
          setForm({
            name: monitor.name,
            description: monitor.description ?? '',
            url: monitor.url,
            httpMethod: monitor.httpMethod,
            expectedStatusCode: monitor.expectedStatusCode,
            intervalSeconds: monitor.intervalSeconds,
            timeoutSeconds: monitor.timeoutSeconds,
            failureThreshold: monitor.failureThreshold,
          })
        }

        // A viewer reaching this URL directly should be told, not handed a form
        // whose every save the backend will reject.
        try {
          const members = await projectApi.listMembers(owningProjectId)
          if (active) {
            setCanManage(canManageMonitors(user, findOwnMembership(user, members)))
          }
        } catch {
          // A system administrator manages a project without belonging to it,
          // so being unable to read the member list is not by itself a refusal.
          if (active) {
            setCanManage(user?.systemRole === 'ADMIN')
          }
        }
      } catch (caught) {
        if (active) {
          setLoadError(caught instanceof ApiError ? caught.message : 'Unable to load the monitor.')
        }
      } finally {
        if (active) {
          setIsLoading(false)
        }
      }
    }

    void load()
    return () => {
      active = false
    }
  }, [mode, monitorId, projectId, user])

  function update<K extends keyof MonitorRequest>(key: K, value: MonitorRequest[K]) {
    setForm((previous) => ({ ...previous, [key]: value }))
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)

    // One rule the browser cannot express with min/max alone. The backend
    // enforces it too and its message wins if this is somehow bypassed.
    if (form.timeoutSeconds >= form.intervalSeconds) {
      setError('Timeout must be shorter than the check interval.')
      return
    }

    setIsSubmitting(true)
    const payload: MonitorRequest = { ...form, description: form.description || null }

    try {
      if (mode === 'create') {
        const created = await monitorApi.create(projectId, payload)
        navigate(`/monitors/${created.id}`, { replace: true })
      } else {
        const updated = await monitorApi.update(monitorId, payload)
        navigate(`/monitors/${updated.id}`, { replace: true })
      }
    } catch (caught) {
      // Show whatever the backend said — it is authoritative, and its message
      // is more specific than anything guessed here.
      setError(caught instanceof ApiError ? caught.message : 'Unable to save the monitor.')
      setIsSubmitting(false)
    }
  }

  if (isLoading) {
    return <LoadingState message="Loading monitor…" />
  }

  if (loadError) {
    return <ErrorState message={loadError} />
  }

  if (!canManage) {
    return (
      <ErrorState message="You need the project admin role to add or change monitors in this project." />
    )
  }

  return (
    <div className="page page--narrow">
      <h1>{mode === 'create' ? 'New monitor' : 'Edit monitor'}</h1>

      {error && (
        <p className="alert alert--error" role="alert">
          {error}
        </p>
      )}

      <form className="card card--form" onSubmit={handleSubmit} noValidate>
        <div className="field">
          <label htmlFor="name">Name</label>
          <input
            id="name"
            type="text"
            required
            minLength={2}
            maxLength={150}
            value={form.name}
            onChange={(event) => update('name', event.target.value)}
          />
        </div>

        <div className="field">
          <label htmlFor="description">Description</label>
          <textarea
            id="description"
            rows={2}
            maxLength={1000}
            value={form.description ?? ''}
            onChange={(event) => update('description', event.target.value)}
          />
        </div>

        <div className="field">
          <label htmlFor="url">URL</label>
          <input
            id="url"
            type="url"
            required
            maxLength={2048}
            placeholder="https://api.example.com/health"
            value={form.url}
            onChange={(event) => update('url', event.target.value)}
          />
          <p className="field__hint">Must be an http or https address.</p>
        </div>

        <div className="field-row">
          <div className="field">
            <label htmlFor="httpMethod">HTTP method</label>
            {/* Only GET exists in the domain today, so the control offers only
                GET rather than implying other methods are supported. */}
            <select
              id="httpMethod"
              value={form.httpMethod}
              onChange={(event) => update('httpMethod', event.target.value as 'GET')}
            >
              <option value="GET">GET</option>
            </select>
          </div>

          <div className="field">
            <label htmlFor="expectedStatusCode">Expected status</label>
            <input
              id="expectedStatusCode"
              type="number"
              required
              min={100}
              max={599}
              value={form.expectedStatusCode}
              onChange={(event) => update('expectedStatusCode', Number(event.target.value))}
            />
          </div>
        </div>

        <div className="field-row">
          <div className="field">
            <label htmlFor="intervalSeconds">Check interval (seconds)</label>
            <input
              id="intervalSeconds"
              type="number"
              required
              min={30}
              max={86400}
              value={form.intervalSeconds}
              onChange={(event) => update('intervalSeconds', Number(event.target.value))}
            />
            <p className="field__hint">Between 30 and 86400.</p>
          </div>

          <div className="field">
            <label htmlFor="timeoutSeconds">Timeout (seconds)</label>
            <input
              id="timeoutSeconds"
              type="number"
              required
              min={1}
              max={30}
              value={form.timeoutSeconds}
              onChange={(event) => update('timeoutSeconds', Number(event.target.value))}
            />
            <p className="field__hint">Must be shorter than the interval.</p>
          </div>

          <div className="field">
            <label htmlFor="failureThreshold">Failure threshold</label>
            <input
              id="failureThreshold"
              type="number"
              required
              min={1}
              max={10}
              value={form.failureThreshold}
              onChange={(event) => update('failureThreshold', Number(event.target.value))}
            />
            <p className="field__hint">Consecutive failures before Down.</p>
          </div>
        </div>

        <div className="form-actions">
          <button type="submit" className="btn btn--primary" disabled={isSubmitting}>
            {isSubmitting ? 'Saving…' : mode === 'create' ? 'Create monitor' : 'Save changes'}
          </button>
          <button type="button" className="btn btn--ghost" onClick={() => navigate(-1)}>
            Cancel
          </button>
        </div>
      </form>
    </div>
  )
}
