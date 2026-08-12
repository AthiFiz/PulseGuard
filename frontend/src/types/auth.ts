export type SystemRole = 'ADMIN' | 'USER'

/** Returned by /auth/register and /auth/me. Never contains a password hash. */
export interface User {
  id: number
  email: string
  displayName: string
  systemRole: SystemRole
  enabled: boolean
  createdAt: string
}

/** The compact user summary that comes back alongside a fresh token. */
export interface AuthUserSummary {
  id: number
  email: string
  displayName: string
  systemRole: SystemRole
}

export interface AuthResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: AuthUserSummary
}

export interface LoginRequest {
  email: string
  password: string
}

export interface RegisterRequest {
  email: string
  password: string
  displayName: string
}
