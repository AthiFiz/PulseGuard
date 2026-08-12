/**
 * Presentation helpers.
 *
 * All backend timestamps are UTC instants; they are rendered in the browser's
 * own timezone here rather than being pasted together anywhere else.
 *
 * Every function returns an em dash for missing data. That matters: a null
 * uptime means "not enough data", and showing 0% instead would claim an outage
 * that never happened.
 */
const EMPTY = '—'

export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return EMPTY
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return EMPTY
  }
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

export function formatUptime(value: number | null | undefined): string {
  return value === null || value === undefined ? EMPTY : `${value.toFixed(2)}%`
}

export function formatMilliseconds(value: number | null | undefined): string {
  return value === null || value === undefined ? EMPTY : `${Math.round(value)} ms`
}

export function formatSeconds(value: number | null | undefined): string {
  return value === null || value === undefined ? EMPTY : `${value} sec`
}

export function formatNumber(value: number | null | undefined): string {
  return value === null || value === undefined ? EMPTY : value.toLocaleString()
}

export function formatText(value: string | null | undefined): string {
  return value === null || value === undefined || value === '' ? EMPTY : value
}

/**
 * Converts a `datetime-local` input value into the ISO instant the API expects.
 *
 * The input gives local wall-clock time with no zone; `new Date` interprets it
 * in the browser's zone, and toISOString converts to UTC — so picking 09:00
 * locally filters from 09:00 locally, which is what the user meant.
 */
export function localInputToIso(value: string): string | null {
  if (!value) {
    return null
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}
