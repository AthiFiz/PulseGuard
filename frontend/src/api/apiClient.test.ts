import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiClient, setUnauthorizedHandler } from './apiClient'
import { getToken, setToken } from '../auth/tokenStorage'

/** Builds a fetch stub returning one canned response. */
function stubFetch(response: Partial<Response> & { json?: () => Promise<unknown> }) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => ({}),
    ...response,
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  setUnauthorizedHandler(null)
})

describe('apiClient', () => {
  it('builds the request against the configured base URL', async () => {
    const fetchMock = stubFetch({ json: async () => ({ ok: true }) })

    await apiClient.get('/api/v1/projects')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('http://localhost:8080/api/v1/projects')
  })

  it('attaches the bearer token to authenticated calls', async () => {
    setToken('a-token')
    const fetchMock = stubFetch({ json: async () => ({}) })

    await apiClient.get('/api/v1/auth/me')

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer a-token')
  })

  it('omits the token on public calls even when one is stored', async () => {
    setToken('a-token')
    const fetchMock = stubFetch({ status: 200, json: async () => ({}) })

    await apiClient.post('/api/v1/auth/login', { email: 'a@b.c', password: 'x' }, true)

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined()
  })

  it('sends a JSON body with the matching content type', async () => {
    const fetchMock = stubFetch({ json: async () => ({}) })

    await apiClient.post('/api/v1/projects', { name: 'Prod' })

    const init = fetchMock.mock.calls[0][1]
    expect(init.method).toBe('POST')
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(init.body).toBe('{"name":"Prod"}')
  })

  it('only sends query parameters that have a value', async () => {
    const fetchMock = stubFetch({ json: async () => ({}) })

    await apiClient.get('/api/v1/monitors/1/checks', {
      page: 0,
      size: 50,
      outcome: null,
      from: '',
      to: undefined,
    })

    // An empty outcome would be rejected by the backend rather than ignored.
    expect(fetchMock.mock.calls[0][0]).toBe(
      'http://localhost:8080/api/v1/monitors/1/checks?page=0&size=50',
    )
  })

  it('returns undefined for 204 rather than trying to parse an empty body', async () => {
    const json = vi.fn()
    stubFetch({ status: 204, json })

    const result = await apiClient.delete('/api/v1/monitors/1')

    expect(result).toBeUndefined()
    expect(json).not.toHaveBeenCalled()
  })

  it('turns an error body into an ApiError carrying the backend code', async () => {
    stubFetch({
      ok: false,
      status: 409,
      json: async () => ({
        timestamp: '2026-08-12T00:00:00Z',
        status: 409,
        code: 'PROJECT_REQUIRES_ADMIN',
        message: 'A project must always have at least one PROJECT_ADMIN',
        path: '/api/v1/projects/1/members/2',
      }),
    })

    await expect(apiClient.delete('/api/v1/projects/1/members/2')).rejects.toMatchObject({
      status: 409,
      code: 'PROJECT_REQUIRES_ADMIN',
      message: 'A project must always have at least one PROJECT_ADMIN',
    })
  })

  it('keeps field errors from a validation failure', async () => {
    stubFetch({
      ok: false,
      status: 400,
      json: async () => ({
        status: 400,
        code: 'VALIDATION_ERROR',
        message: 'Request validation failed',
        path: '/api/v1/auth/register',
        errors: [{ field: 'password', message: 'Password must be between 8 and 128 characters' }],
      }),
    })

    try {
      await apiClient.post('/api/v1/auth/register', {}, true)
      expect.unreachable('should have thrown')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).fieldErrors[0].field).toBe('password')
    }
  })

  /** A proxy returning HTML must not surface as "[object Object]". */
  it('falls back to a readable message when the error body is not JSON', async () => {
    stubFetch({
      ok: false,
      status: 502,
      json: async () => {
        throw new Error('not json')
      },
    })

    await expect(apiClient.get('/api/v1/projects')).rejects.toMatchObject({
      status: 502,
      code: 'UNKNOWN_ERROR',
      message: 'Request failed (HTTP 502)',
    })
  })

  it('clears the token and notifies once when an authenticated call is rejected', async () => {
    setToken('expired-token')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    stubFetch({ ok: false, status: 401, json: async () => ({ code: 'AUTHENTICATION_REQUIRED', message: 'Authentication is required' }) })

    await expect(apiClient.get('/api/v1/projects')).rejects.toBeInstanceOf(ApiError)

    expect(getToken()).toBeNull()
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  /**
   * A failed login is a 401 too. Treating it as an expired session would fight
   * with the login form trying to show "invalid email or password".
   */
  it('does not treat a rejected login as an expired session', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    stubFetch({ ok: false, status: 401, json: async () => ({ code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' }) })

    await expect(
      apiClient.post('/api/v1/auth/login', { email: 'a@b.c', password: 'wrong' }, true),
    ).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })

    expect(onUnauthorized).not.toHaveBeenCalled()
  })
})
