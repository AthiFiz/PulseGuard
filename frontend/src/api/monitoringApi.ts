import { apiClient } from './apiClient'
import type { PageResponse } from '../types/api'
import type {
  CheckHistoryQuery,
  MonitorCheck,
  MonitorStatistics,
  ProjectDashboard,
} from '../types/monitoring'

export const monitoringApi = {
  /**
   * Paginated check history, newest first. Empty filters are dropped by the
   * client rather than sent as blank parameters.
   */
  checkHistory: (monitorId: number, query: CheckHistoryQuery = {}) =>
    apiClient.get<PageResponse<MonitorCheck>>(`/api/v1/monitors/${monitorId}/checks`, {
      page: query.page,
      size: query.size,
      from: query.from,
      to: query.to,
      outcome: query.outcome,
    }),

  /** With no range this covers all recorded history. */
  statistics: (monitorId: number) =>
    apiClient.get<MonitorStatistics>(`/api/v1/monitors/${monitorId}/statistics`),

  /** With no range this defaults to the last 24 hours, decided by the backend. */
  dashboard: (projectId: number) =>
    apiClient.get<ProjectDashboard>(`/api/v1/projects/${projectId}/dashboard`),
}
