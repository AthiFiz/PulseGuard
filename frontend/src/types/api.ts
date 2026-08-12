/**
 * The single error shape every PulseGuard endpoint returns, mirroring the
 * backend's ApiErrorResponse record.
 *
 * `errors` carries per-field detail for validation failures and is absent
 * otherwise, because the backend serialises the error body with NON_NULL.
 */
export interface ApiErrorResponse {
  timestamp: string
  status: number
  code: string
  message: string
  path: string
  errors?: ApiFieldError[]
}

export interface ApiFieldError {
  field: string
  message: string
}

/** The backend's stable pagination envelope. */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}
