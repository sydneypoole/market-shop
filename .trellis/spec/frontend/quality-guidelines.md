# Frontend Quality Guidelines

## Required

- `strict: true` and `noEmit: true` in each TypeScript configuration.
- `pnpm --filter @market-shop/admin test`, `pnpm --filter @market-shop/admin typecheck`, and `pnpm --filter @market-shop/admin build` pass before handoff.
- Responsive verification at desktop admin widths.
- Loading, empty, error, and disabled states for network-backed actions.
- Admin cookie session remains isolated from the miniprogram header token (`market-shop-user-token`).

## Forbidden

- `any`, `@ts-ignore`, unchecked API assertions, generated `.js` inside `src`, or committed `dist`.
- Client-only authorization or client-calculated commission as authoritative data.
- Online-payment controls, withdrawal controls, or copy suggesting points are cash.
- Secret keys, WeChat app secrets, private object keys, or permanent signed URLs in frontend code.
- `console.log` in committed source.

## Review

Review API failure handling, duplicate-submit prevention, mobile navigation, focus behavior, text overflow, state refresh after mutations, and the absence of cross-application token leakage.
