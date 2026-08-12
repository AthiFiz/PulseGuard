import type { User } from '../types/auth'
import type { ProjectMember } from '../types/project'

/**
 * Whether the UI should offer management controls for a project.
 *
 * This is a user-experience decision only. **The backend remains the security
 * boundary** — hiding a button prevents confusion, not attack. Every management
 * call is independently authorized server-side, and a 403 coming back is
 * handled rather than assumed impossible.
 *
 * A system administrator manages any project without being a member of it.
 */
export function canManageProject(
  user: User | null,
  membership: ProjectMember | null | undefined,
): boolean {
  if (!user) {
    return false
  }
  if (user.systemRole === 'ADMIN') {
    return true
  }
  return membership?.role === 'PROJECT_ADMIN'
}

/**
 * Monitor permissions are identical to project permissions — a monitor has no
 * membership of its own, so access is entirely inherited from its project.
 */
export const canManageMonitors = canManageProject

/** Finds the caller's own membership row, if they have one. */
export function findOwnMembership(
  user: User | null,
  members: ProjectMember[],
): ProjectMember | null {
  if (!user) {
    return null
  }
  return members.find((member) => member.userId === user.id) ?? null
}
