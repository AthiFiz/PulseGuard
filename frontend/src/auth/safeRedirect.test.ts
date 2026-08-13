import { describe, expect, it } from 'vitest'
import { safeRedirectPath } from './safeRedirect'

/**
 * The login page resumes an interrupted journey by navigating to a path it was
 * handed. That path started life in the address bar, so somebody who can get a
 * user to open a link chooses it — which is an open redirect unless it is
 * checked first.
 */
describe('safeRedirectPath', () => {
  describe('ordinary in-app destinations survive intact', () => {
    it.each([
      '/projects',
      '/projects/10/monitors',
      '/monitors/25',
      '/monitors/25/edit',
      '/projects/10/incidents?status=OPEN',
      '/projects/10/dashboard#summary',
    ])('keeps %s', (path) => {
      expect(safeRedirectPath(path)).toBe(path)
    })
  })

  describe('anything that would leave the site falls back', () => {
    it.each([
      ['//evil.example', 'protocol-relative'],
      ['/\\evil.example', 'backslash, which the browser normalises to a slash'],
      ['\\\\evil.example', 'a UNC-style path'],
      ['https://evil.example/phish', 'an absolute URL'],
      ['//evil.example/projects', 'protocol-relative with a plausible tail'],
      ['/projects\\@evil.example', 'a backslash buried mid-path'],
    ])('refuses %s (%s)', (path) => {
      expect(safeRedirectPath(path)).toBe('/projects')
    })
  })

  describe('anything not rooted at the site falls back', () => {
    it.each(['projects', 'evil.example', 'javascript:alert(1)', 'data:text/html,x'])(
      'refuses %s',
      (path) => {
        expect(safeRedirectPath(path)).toBe('/projects')
      },
    )
  })

  /**
   * Browsers strip these out of a URL before parsing it, so a path that looks
   * harmless in a check can mean something else by the time it is followed.
   */
  it('refuses paths containing characters a browser would strip', () => {
    expect(safeRedirectPath('/\tevil.example')).toBe('/projects')
    expect(safeRedirectPath('/\nevil.example')).toBe('/projects')
    expect(safeRedirectPath('/\revil.example')).toBe('/projects')
    expect(safeRedirectPath('/pro\u0000jects')).toBe('/projects')
  })

  /** A space is not one of them, and a path may legitimately contain one. */
  it('does not reject an ordinary space', () => {
    expect(safeRedirectPath('/projects/my project')).toBe('/projects/my project')
  })

  describe('nothing to resume', () => {
    it.each([undefined, null, ''])('falls back for %s', (value) => {
      expect(safeRedirectPath(value)).toBe('/projects')
    })
  })

  it('accepts a caller-supplied fallback', () => {
    expect(safeRedirectPath('//evil.example', '/login')).toBe('/login')
    expect(safeRedirectPath(undefined, '/login')).toBe('/login')
  })
})
