export type AdminPermissionRequirement = readonly [string, ...string[]]

export function hasAnyPermission(
  requiredPermissions: AdminPermissionRequirement,
  check: (permission: string) => boolean
) {
  return requiredPermissions.some(permission => check(permission))
}
