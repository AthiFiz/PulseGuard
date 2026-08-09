import { useEffect, useState } from 'react'
import { fetchSystemInfo } from './api/systemApi'
import { ConnectionStatus, type ConnectionState } from './components/ConnectionStatus'
import './App.css'

export default function App() {
  const [controlApiState, setControlApiState] = useState<ConnectionState>('CHECKING')

  useEffect(() => {
    let active = true

    fetchSystemInfo()
      .then(() => {
        if (active) {
          setControlApiState('AVAILABLE')
        }
      })
      .catch(() => {
        if (active) {
          setControlApiState('UNAVAILABLE')
        }
      })

    return () => {
      active = false
    }
  }, [])

  return (
    <main className="app">
      <header className="app-header">
        <h1>PulseGuard</h1>
        <p className="tagline">API Monitoring &amp; Incident Management Platform</p>
      </header>

      <section className="panel">
        <h2>System Status</h2>
        <ConnectionStatus label="Control API" state={controlApiState} />
      </section>

      <footer className="app-footer">Stage 1 — Project Foundation</footer>
    </main>
  )
}
