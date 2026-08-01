import type { OrderActorCapabilities } from './types'

export const noOrderActions: Readonly<OrderActorCapabilities> = Object.freeze({
  canReceive: false,
  canUploadProof: false,
  canCancel: false,
  canSuperiorDecide: false
})

/**
 * Treat absent or malformed server capability flags as denied. The storefront
 * deliberately does not reconstruct actor identity or order transition rules.
 */
export function resolveOrderActions(
  capabilities?: Partial<OrderActorCapabilities>
): OrderActorCapabilities {
  return {
    canReceive: capabilities?.canReceive === true,
    canUploadProof: capabilities?.canUploadProof === true,
    canCancel: capabilities?.canCancel === true,
    canSuperiorDecide: capabilities?.canSuperiorDecide === true
  }
}
