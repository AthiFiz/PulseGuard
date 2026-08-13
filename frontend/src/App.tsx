import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthContext'
import { ProtectedRoute, PublicOnlyRoute } from './auth/ProtectedRoute'
import { AppLayout } from './components/layout/AppLayout'
import { IncidentDetailsPage } from './pages/IncidentDetailsPage'
import { LoginPage } from './pages/LoginPage'
import { MonitorDetailsPage } from './pages/MonitorDetailsPage'
import { MonitorFormPage } from './pages/MonitorFormPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { ProjectDashboardPage } from './pages/ProjectDashboardPage'
import { ProjectIncidentsPage } from './pages/ProjectIncidentsPage'
import { ProjectLayout } from './pages/ProjectLayout'
import { ProjectMembersPage } from './pages/ProjectMembersPage'
import { ProjectMonitorsPage } from './pages/ProjectMonitorsPage'
import { ProjectSettingsPage } from './pages/ProjectSettingsPage'
import { ProjectsPage } from './pages/ProjectsPage'
import { RegisterPage } from './pages/RegisterPage'

/**
 * A plain client-side SPA. Every route below `ProtectedRoute` requires a
 * verified session; the two public routes bounce a signed-in user onward.
 */
export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route element={<PublicOnlyRoute />}>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
          </Route>

          <Route element={<ProtectedRoute />}>
            <Route element={<AppLayout />}>
              <Route path="/" element={<Navigate to="/projects" replace />} />
              <Route path="/projects" element={<ProjectsPage />} />

              {/* The project shell loads the project once and works out what
                  the current user is allowed to do with it. */}
              <Route path="/projects/:projectId" element={<ProjectLayout />}>
                <Route index element={<Navigate to="dashboard" replace />} />
                <Route path="dashboard" element={<ProjectDashboardPage />} />
                <Route path="monitors" element={<ProjectMonitorsPage />} />
                <Route path="incidents" element={<ProjectIncidentsPage />} />
                <Route path="members" element={<ProjectMembersPage />} />
                <Route path="settings" element={<ProjectSettingsPage />} />
              </Route>

              {/* Creating needs the project in the path; everything else
                  identifies the monitor by its own id. */}
              <Route
                path="/projects/:projectId/monitors/new"
                element={<MonitorFormPage mode="create" />}
              />
              <Route path="/monitors/:monitorId" element={<MonitorDetailsPage />} />
              <Route path="/monitors/:monitorId/edit" element={<MonitorFormPage mode="edit" />} />

              {/* An incident is addressed by its own id: it belongs to a
                  monitor, which belongs to a project, and the API resolves
                  that chain rather than trusting a project id in the path. */}
              <Route path="/incidents/:incidentId" element={<IncidentDetailsPage />} />

              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}
