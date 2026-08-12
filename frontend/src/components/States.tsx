import type { ReactNode } from 'react'

/** Shown while a page's data is in flight, so nothing renders as undefined. */
export function LoadingState({ message = 'Loading…' }: { message?: string }) {
  return (
    <div className="state" role="status">
      <span className="spinner" aria-hidden="true" />
      <p>{message}</p>
    </div>
  )
}

/**
 * A failed load, with a way out. Leaving the user with a blank page and an
 * error in the console is not an error state.
 */
export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="state state--error" role="alert">
      <p>{message}</p>
      {onRetry && (
        <button type="button" className="btn btn--secondary" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  )
}

/** A successful load that found nothing, usually with the action to fix that. */
export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="state state--empty">
      <p className="state__title">{title}</p>
      {description && <p className="state__description">{description}</p>}
      {action}
    </div>
  )
}
