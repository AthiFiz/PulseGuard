/**
 * Central API configuration.
 *
 * The Control API base URL comes from the VITE_API_BASE_URL environment
 * variable (see .env.example). Components must never hardcode backend URLs.
 */
export const API_BASE_URL: string =
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export function apiUrl(path: string): string {
  return `${API_BASE_URL.replace(/\/$/, '')}${path}`
}
