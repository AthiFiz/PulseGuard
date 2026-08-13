/**
 * An incident is one continuous outage, not one failed check. A monitor that
 * fails ten times in a row before recovering produces a single incident.
 *
 * Deliberately separate from `MonitorStatus`: a monitor is UP right now while
 * the same project holds a long history of RESOLVED incidents.
 */
export type IncidentStatus = 'OPEN' | 'RESOLVED'

export interface Incident {
  id: number
  projectId: number
  monitorId: number
  monitorName: string
  status: IncidentStatus
  /** The timestamp of the check that caused the outage. */
  openedAt: string
  /** Null while the incident is OPEN — never a fabricated end time. */
  resolvedAt: string | null
  openingCheckId: number | null
  resolutionCheckId: number | null
}

/** Filters accepted by the project incident list. Omitted values are dropped. */
export interface IncidentQuery {
  page?: number
  size?: number
  status?: IncidentStatus | null
  from?: string | null
  to?: string | null
}
