/** Where to send someone when the remembered destination cannot be trusted. */
const DEFAULT_DESTINATION = '/projects'

/**
 * Anything a browser strips or rewrites while parsing a URL. A path containing
 * one of these can mean something different by the time it is used than it does
 * when it is inspected here.
 */
// eslint-disable-next-line no-control-regex
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/

/**
 * Reduces a remembered destination to a same-site path, or gives up and returns
 * the default.
 *
 * `ProtectedRoute` stores the location it interrupted so that logging in resumes
 * the journey. That value originates in the address bar, so somebody else can
 * choose it: send a link to `https://pulseguard.example/\evil.com`, the guard
 * bounces to the login page carrying `/\evil.com`, and the redirect after a
 * successful login leaves the site — with the user's trust already established
 * by the real login form they just used.
 *
 * A browser normalises a leading backslash to a slash, so `/\evil.com` and
 * `//evil.com` both end up as protocol-relative URLs pointing at another host.
 * Anything that is not a single-slash-rooted path is therefore refused.
 *
 * This overlaps with a fix React Router made in 7.18 (GHSA-wrjc-x8rr-h8h6).
 * Keeping it here is deliberate: it holds whatever the router version does, and
 * the rule — never navigate off-site on the strength of a URL somebody else
 * handed the user — belongs to this application rather than to a dependency.
 */
export function safeRedirectPath(
  pathname: string | undefined | null,
  fallback: string = DEFAULT_DESTINATION,
): string {
  if (!pathname) {
    return fallback
  }

  // Must be rooted. A bare "evil.com" is relative, and so is "javascript:…"
  // by the time a router hands it to the history API.
  if (!pathname.startsWith('/')) {
    return fallback
  }

  // "//host" is protocol-relative; "/\host" becomes the same once the browser
  // normalises it. Both leave the site.
  if (pathname.startsWith('//') || pathname.startsWith('/\\')) {
    return fallback
  }

  // A backslash anywhere is either an escape attempt or a Windows path, and
  // neither is a route this application serves.
  if (pathname.includes('\\')) {
    return fallback
  }

  if (CONTROL_CHARACTERS.test(pathname)) {
    return fallback
  }

  return pathname
}
