import { apiClient } from './apiClient'
import type { PageResponse } from '../types/api'
import type { Incident, IncidentQuery } from '../types/incident'

/**
 * Reading incidents. There is no create, update or delete: incidents are
 * written by the Monitor Worker from observed checks, and the API offers
 * nothing to write to.
 */
export const incidentApi = {
  /** Paginated project history, newest first. Defaults to 20 per page. */
  listForProject: (projectId: number, query: IncidentQuery = {}) =>
    apiClient.get<PageResponse<Incident>>(`/api/v1/projects/${projectId}/incidents`, {
      page: query.page,
      size: query.size,
      status: query.status,
      from: query.from,
      to: query.to,
    }),

  get: (incidentId: number) => apiClient.get<Incident>(`/api/v1/incidents/${incidentId}`),
}
