# Native Miniprogram FirstUI Contract

## Scope / Trigger

Apply this contract whenever changing `miniprogram/app.*`, a page WXML/WXSS/JSON file, a shared miniprogram component, or vendored FirstUI source.

## Source and build boundary

- The only approved UI source is the public Apache-2.0 FirstUI-weixin V2.4.0 commit `fa7863720afcf591aaf3ba6de29c42a88c6dde80`.
- Vendor only components actually used by a page or project wrapper under `miniprogram/components/firstui/`. Preserve `LICENSE` and `UPSTREAM.md`; upstream component files remain unmodified. Put brand changes in `app.wxss` or project-owned wrappers.
- The native miniprogram has no npm build. Keep `nodeModules`, `bundle`, and `packNpmManually` disabled.
- Do not add VIP/non-public `fui-upload`, `fui-timeaxis`, `fui-nav-bar`, or `fui-searchbar`. Keep native navigation/tabBar, `open-type="contact"`, region picker, media selection/preview, and page routing.
- Omit `style: "v2"` from `app.json`. Import the theme from `app.wxss` with the relative path `./components/firstui/fui-theme/fui-theme.wxss`.

## Theme and page composition

- Map FirstUI `--fui-*` variables to the single macro-brand token set in `app.wxss`; page-local source must reuse those tokens instead of introducing a new accent palette.
- Every registered page uses the project-owned `brand-shell` wrapper. The wrapper may provide background, safe-area and loading presentation, but must not own business requests or introduce vertical overflow/scroll containers that break long lists or `onReachBottom`.
- Prefer the shared `empty`, `goods-card`, `stepper`, and `sku-sheet` business components. They own the mapping between FirstUI presentation events and stable page events.
- Keep primary actions at least 88rpx high, use explicit disabled/loading states, preserve readable contrast, add accessibility labels to icon-only controls, and include `env(safe-area-inset-bottom)` for fixed bottom actions.
- Network failure is not an empty success. Existing page error/retry state remains visible, while server-provided content, rules, statuses and capabilities remain authoritative.

## Event adapters

FirstUI and native event payloads are not interchangeable:

| Component event | FirstUI detail | Project-facing contract |
|---|---|---|
| `fui-input-number change` | `{ value, index, params }` | shared `stepper` emits scalar quantity |
| `fui-tag click` | `{ index }` | SKU wrapper resolves the SKU, then emits the existing confirm payload |
| `fui-button click` | `{ index }` | wrapper action ignores presentation detail and calls the existing handler |
| `fui-bottom-popup close` | `{}` | SKU wrapper emits the existing `close` event |
| `fui-input input` | scalar value | page adapter is required before using a handler that expects `e.detail.value` |
| `fui-tabs change` | `{ index }` | page adapter is required before using dataset/tab-id handlers |

Do not bind a FirstUI component directly to an existing page handler until its payload shape has been compared and tested.

## Business invariants

Visual migration must not change:

- `market-shop-user-token`, 401 cleanup/reLaunch, or HTTP status/stable-code preservation;
- 409 authoritative reload, actor capability gates, or unknown-status safe behavior;
- checkout/after-sale `clientRequestId` lifetime;
- incremental cart quantity and confirmed zero-quantity deletion;
- address `version` plus `globalData`/`eventChannel` return channels;
- proof capability limits, stage-specific `proofType`, short-lived URL refresh, native upload and preview;
- superior offline-refund JSON body, dynamic rules/content, or native customer service.

## Quality gate

Run `pnpm test:miniprogram` and `git diff --check`. Static tests must:

- discover components through JSON files whose `component` field is `true`, rather than assuming every vendor JavaScript utility is a component bundle;
- resolve every page/component registration and WXML event handler;
- assert the source commit/license, theme import/mapping, no VIP directories, all-page wrapper use, adapter payloads and package ignores;
- calculate the upload-source size after `packOptions.ignore` and keep the main package below 2 MiB.

Finally compile and inspect the project in WeChat Developer Tools and perform the release-checklist device flow; Node static tests do not emulate the WXML/WXSS renderer.
