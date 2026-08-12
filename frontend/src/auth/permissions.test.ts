import { describe, expect, it } from 'vitest'
import { canManageProject, findOwnMembership } from './permissions'
import type { User } from '../types/auth'
import type { ProjectMember } from '../types/project'

const normalUser: User = {
  id: 1,
  email: 'user@example.com',
  displayName: 'Normal User',
  systemRole: 'USER',
  enabled: true,
  createdAt: '2026-08-01T00:00:00Z',
}

const systemAdmin: User = { ...normalUser, id: 2, systemRole: 'ADMIN' }

function membership(userId: number, role: ProjectMember['role']): ProjectMember {
  return {
    memberId: 100 + userId,
    userId,
    email: 'member@example.com',
    displayName: 'Member',
    role,
    joinedAt: '2026-08-01T00:00:00Z',
  }
}

describe('canManageProject', () => {
  it('allows a project admin', () => {
    expect(canManageProject(normalUser, membership(1, 'PROJECT_ADMIN'))).toBe(true)
  })

  it('refuses a viewer', () => {
    expect(canManageProject(normalUser, membership(1, 'VIEWER'))).toBe(false)
  })

  /** A system administrator manages any project without belonging to it. */
  it('allows a system admin with no membership at all', () => {
    expect(canManageProject(systemAdmin, null)).toBe(true)
  })

  it('refuses a normal user with no membership', () => {
    expect(canManageProject(normalUser, null)).toBe(false)
  })

  it('refuses when nobody is signed in', () => {
    expect(canManageProject(null, membership(1, 'PROJECT_ADMIN'))).toBe(false)
  })
})

describe('findOwnMembership', () => {
  it('finds the signed-in user among the members', () => {
    const members = [membership(9, 'PROJECT_ADMIN'), membership(1, 'VIEWER')]

    expect(findOwnMembership(normalUser, members)?.role).toBe('VIEWER')
  })

  it('returns null when the user is not a member', () => {
    expect(findOwnMembership(normalUser, [membership(9, 'PROJECT_ADMIN')])).toBeNull()
  })
})
