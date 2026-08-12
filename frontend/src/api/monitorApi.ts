import { apiClient } from './apiClient'
import type { Monitor, MonitorRequest } from '../types/monitor'

export const monitorApi = {
  listForProject: (projectId: number) =>
    apiClient.get<Monitor[]>(`/api/v1/projects/${projectId}/monitors`),

  create: (projectId: number, request: MonitorRequest) =>
    apiClient.post<Monitor>(`/api/v1/projects/${projectId}/monitors`, request),

  get: (monitorId: number) => apiClient.get<Monitor>(`/api/v1/monitors/${monitorId}`),

  update: (monitorId: number, request: MonitorRequest) =>
    apiClient.put<Monitor>(`/api/v1/monitors/${monitorId}`, request),

  /** Both are idempotent on the backend and return the updated monitor. */
  pause: (monitorId: number) => apiClient.post<Monitor>(`/api/v1/monitors/${monitorId}/pause`),

  resume: (monitorId: number) => apiClient.post<Monitor>(`/api/v1/monitors/${monitorId}/resume`),

  remove: (monitorId: number) => apiClient.delete<void>(`/api/v1/monitors/${monitorId}`),
}
