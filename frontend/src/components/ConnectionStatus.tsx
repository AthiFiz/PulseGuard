export type ConnectionState = 'CHECKING' | 'AVAILABLE' | 'UNAVAILABLE'

const LABELS: Record<ConnectionState, string> = {
  CHECKING: 'Checking...',
  AVAILABLE: 'Available',
  UNAVAILABLE: 'Unavailable',
}

interface ConnectionStatusProps {
  label: string
  state: ConnectionState
}

export function ConnectionStatus({ label, state }: ConnectionStatusProps) {
  return (
    <div className="status-row">
      <span className="status-label">{label}</span>
      <span className={`status-value status-value--${state.toLowerCase()}`}>
        <span className="status-dot" aria-hidden="true" />
        {LABELS[state]}
      </span>
    </div>
  )
}
