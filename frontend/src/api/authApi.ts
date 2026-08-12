import { apiClient } from './apiClient'
import type { AuthResponse, LoginRequest, RegisterRequest, User } from '../types/auth'

export const authApi = {
  /** Public. Creates a normal USER; no token is issued here. */
  register: (request: RegisterRequest) =>
    apiClient.post<User>('/api/v1/auth/register', request, true),

  /** Public. */
  login: (request: LoginRequest) =>
    apiClient.post<AuthResponse>('/api/v1/auth/login', request, true),

  /** Requires a token; the user is resolved from the token, not a parameter. */
  me: () => apiClient.get<User>('/api/v1/auth/me'),
}
