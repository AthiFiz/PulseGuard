/**
 * Test environment setup.
 *
 * sessionStorage is cleared between tests so a token stored by one cannot leak
 * into the next, and fetch is reset so an unstubbed call fails loudly rather
 * than silently hitting the network.
 */
import { afterEach, beforeEach, vi } from 'vitest'
import { cleanup, configure } from '@testing-library/react'

/**
 * How long `findBy*` and `waitFor` may keep polling before giving up.
 *
 * Testing Library defaults to one second. That is not enough for these pages:
 * a screen like the monitor detail view only finishes rendering after three
 * *sequential* mocked round trips — the session check, then the monitor and
 * its statistics, then the project's members to decide what the user may do —
 * and each one needs a React render between it and the next. On an unloaded
 * machine that takes milliseconds; on a busy one it exceeded the second and
 * the suite failed for reasons that had nothing to do with the code.
 *
 * Raising the ceiling does not slow anything down. These helpers poll and
 * return the moment the element appears, so a passing test finishes exactly as
 * fast as before; only the patience before declaring failure changes. Five
 * seconds is enough headroom for a saturated laptop while still failing
 * promptly when something is genuinely broken.
 *
 * This is deliberately not a sleep, and not a per-test timeout sprinkled where
 * failures happened to show up — the tightness was global, so the fix is.
 */
configure({ asyncUtilTimeout: 5000 })

beforeEach(() => {
  sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
