/**
 * Test environment setup.
 *
 * sessionStorage is cleared between tests so a token stored by one cannot leak
 * into the next, and fetch is reset so an unstubbed call fails loudly rather
 * than silently hitting the network.
 */
import { afterEach, beforeEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'

beforeEach(() => {
  sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
