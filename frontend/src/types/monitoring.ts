import type { MonitorStatus } from './monitor'

export type MonitorCheckOutcome = 'SUCCESS' | 'FAILURE'

export type MonitorCheckErrorType =
  | 'TIMEOUT'
  | 'CONNECTION_ERROR'
  | 'UNEXPECTED_STATUS'
  | 'DNS_ERROR'
  | 'BLOCKED_ADDRESS'
  | 'UNKNOWN'

/**
 * One recorded check.
 *
 * `httpStatusCode` and `responseTimeMs` are null when no response arrived —
 * a DNS failure or a blocked destination never got that far.
 */
export interface MonitorCheck {
  id: number
  checkedAt: string
  outcome: MonitorCheckOutcome
  httpStatusCode: number | null
  responseTimeMs: number | null
  errorType: MonitorCheckErrorType | null
  errorMessage: string | null
}

/**
 * Aggregate figures for one monitor.
 *
 * The nullable numbers mean "no data", not zero: a monitor nobody has checked
 * has unknown availability rather than 0% availability.
 */
export interface MonitorStatistics {
  monitorId: number
  from: string | null
  to: string | null
  totalChecks: number
  successfulChecks: number
  failedChecks: number
  uptimePercentage: number | null
  averageResponseTimeMs: number | null
  minimumResponseTimeMs: number | null
  maximumResponseTimeMs: number | null
  lastCheckedAt: string | null
  currentStatus: MonitorStatus
}

export interface TimeWindow {
  from: string
  to: string
}

/** Current state, unaffected by the dashboard's time window. */
export interface MonitorStatusCounts {
  total: number
  up: number
  down: number
  unknown: number
  paused: number
}

/** Check figures for the dashboard window. */
export interface ProjectCheckStatistics {
  total: number
  successful: number
  failed: number
  uptimePercentage: number | null
  averageResponseTimeMs: number | null
}

export interface RecentFailure {
  monitorId: number
  monitorName: string
  checkedAt: string
  httpStatusCode: number | null
  errorType: MonitorCheckErrorType | null
  errorMessage: string | null
}

export interface ProjectDashboard {
  projectId: number
  generatedAt: string
  window: TimeWindow
  monitors: MonitorStatusCounts
  /**
   * Outages that have not ended yet, whenever they began. Current state like
   * the monitor counts above, deliberately not filtered by `window`.
   */
  openIncidents: number
  checks: ProjectCheckStatistics
  recentFailures: RecentFailure[]
}

/** Filters accepted by the check-history endpoint. */
export interface CheckHistoryQuery {
  page?: number
  size?: number
  from?: string | null
  to?: string | null
  outcome?: MonitorCheckOutcome | null
}
