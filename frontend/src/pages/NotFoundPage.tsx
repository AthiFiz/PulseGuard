import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return (
    <div className="page page--narrow">
      <h1>Page not found</h1>
      <p>That address does not match anything in PulseGuard.</p>
      <Link to="/projects" className="btn btn--primary">
        Back to projects
      </Link>
    </div>
  )
}
