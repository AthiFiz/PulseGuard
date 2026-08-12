export type MonitorStatus = 'UP' | 'DOWN' | 'UNKNOWN' | 'PAUSED'

/** Only GET is supported by the monitoring engine at this stage. */
export type MonitorHttpMethod = 'GET'

export interface Monitor {
  id: number
  projectId: number
  name: string
  description: string | null
  url: string
  httpMethod: MonitorHttpMethod
  expectedStatusCode: number
  intervalSeconds: number
  timeoutSeconds: number
  failureThreshold: number
  consecutiveFailures: number
  currentStatus: MonitorStatus
  lastCheckedAt: string | null
  nextCheckAt: string | null
  createdAt: string
  updatedAt: string
}

/**
 * Creating and updating a monitor take the same shape.
 *
 * Operational fields are deliberately absent: status, failure counts and check
 * timestamps belong to the monitoring engine and the API will not accept them.
 */
export interface MonitorRequest {
  name: string
  description?: string | null
  url: string
  httpMethod: MonitorHttpMethod
  expectedStatusCode: number
  intervalSeconds: number
  timeoutSeconds: number
  failureThreshold: number
}
