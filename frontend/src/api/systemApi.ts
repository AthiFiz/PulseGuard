import { apiUrl } from './config'

export interface SystemInfo {
  application: string
  status: string
}

/**
 * Calls the Control API system information endpoint.
 * Throws if the request fails or returns a non-successful status.
 */
export async function fetchSystemInfo(): Promise<SystemInfo> {
  const response = await fetch(apiUrl('/api/v1/system/info'))

  if (!response.ok) {
    throw new Error(`Control API responded with HTTP ${response.status}`)
  }

  return (await response.json()) as SystemInfo
}
