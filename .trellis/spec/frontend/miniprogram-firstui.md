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

- The visual mode is **Redesign - Preserve**. Preserve the macro-brand logo, name, information architecture, native capabilities and copy intent. The current design read is premium commerce with `DESIGN_VARIANCE: 6`, `MOTION_INTENSITY: 4`, and `VISUAL_DENSITY: 5`.
- Apply the installed `design-taste-frontend` skill only as an audit/anti-slop/pre-flight guide. Its React/Tailwind implementation defaults do not apply to a native WeChat miniprogram; FirstUI remains the one component system.
- Map FirstUI `--fui-*` variables to the single macro-brand token set in `app.wxss`. Use cold pearl/smoke neutrals with `#7A284F` as the only decorative/interactive accent; success, warning and danger colors are reserved for real semantic states. Page-local source must reuse tokens instead of introducing another palette.
- Use the system Chinese sans-serif stack for headings, body and tabular prices. Do not restore Songti/STSong or use `.serif` as a premium-design shortcut.
- Shape lock: content cards use the shared card radius, form controls use the smaller control radius, primary/secondary buttons may be pill-shaped, and circles are reserved for avatars or true icon buttons. Prefer whitespace or one divider over nested cards and decorative shadows.
- Remove decorative one-character stamps, CSS-drawn icons, text arrows and image-overlay branding pills. Use the pinned `fui-icon` family with an accessible label on the surrounding control. Real product/content media remains the main visual asset.
- Every registered page uses the project-owned `brand-shell` wrapper. The wrapper may provide background, safe-area and loading presentation, but must not own business requests or introduce vertical overflow/scroll containers that break long lists or `onReachBottom`.
- Prefer the shared `empty`, `goods-card`, `stepper`, and `sku-sheet` business components. They own the mapping between FirstUI presentation events and stable page events.
- Keep primary actions at least 88rpx high, use explicit disabled/loading states, preserve readable contrast, add accessibility labels to icon-only controls, and include `env(safe-area-inset-bottom)` for fixed bottom actions.
- Motion at level 4 is limited to motivated opacity/transform feedback around 160-240ms. Every project-owned transition must have a `prefers-reduced-motion: reduce` fallback; do not add scroll hijacking or perpetual decoration.
- Network failure is not an empty success. Existing page error/retry state remains visible, while server-provided content, rules, statuses and capabilities remain authoritative.

## Icon assets

- Page and wrapper icons use the unchanged vendored `fui-icon` component. A page registers it locally when rendered; do not add a second icon family or hand-draw SVG/CSS glyphs.
- Native tabBar cannot render a component. Generate its outline/fill PNG pairs from the pinned FirstUI font with `python3 scripts/generate-miniprogram-tab-icons.py`.
- Tab mappings are `home/home-fill`, `classify/classify-fill`, `cart/cart-fill`, and `my/my-fill`. Images are 81 x 81 RGBA, use `app.json` normal/selected colors, stay below 40 KiB, and are byte-identical in `miniprogram/assets/tab/` and `docs/design/miniprogram/icons/`.

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
- identity-entry separation: `pages/login/login` sends only a fresh temporary WeChat code and goes directly home. Public `pages/register/register` renders one invite input and one register button; after a local non-empty check it obtains a fresh `wx.login` code and posts exactly `{code, inviteCode}` to the separate registration route, where the backend decides invitation validity. Explicit `mode=sponsor` replaces the invite with a typed one-time claim secret without exposing a public toggle or putting the secret in a URL.
- one-click registration: loading disables repeat submission; failure retains the invite but retry always obtains another `wx.login` code, never a replay. Registration does not render or call phone, avatar, nickname, `getUserProfile` or `getUserInfo` capabilities. The unique `宏杉会员-{publicId}` platform nickname and null avatar are authoritative backend values, and completion `switchTab`s home.
- optional profile editing: the independent `pages/profile/edit` is reachable from the profile tab, preloads authoritative `/membership/me`, uses only `chooseAvatar`, `input type="nickname"`, `wx.requirePrivacyAuthorize`, and `wx.openPrivacyContract`, and never calls `getPhoneNumber`, `wx.login`, `getUserProfile`, or `getUserInfo`. No-change confirmation and successful save `switchTab` home. A changed nickname is saved before avatar upload; after nickname success an avatar retry uploads only the in-memory temporary file and never puts that path in JSON, navigation URLs, storage, or logs.
- profile-edit presentation: `pages/profile/edit` uses exactly one primary `surface`; `edit.wxss` directly imports `../../styles/auth-flow.wxss` and directly defines every page-specific selector instead of importing another page stylesheet. Preserve the native `open-type="chooseAvatar"`/`bindchooseavatar`, `input type="nickname"`/`bindinput`, accepted privacy-checkbox, and privacy-contract bindings. Exactly one of the page or `brand-shell` owns bottom safe-area padding, and static tests lock these structure, binding, stylesheet, and single-owner contracts.
- member identity rendering: the profile tab prefers the authoritative `/membership/me` nickname and stable same-origin avatar, resolves relative media URLs through the shared helper, and falls back to the nickname initial rather than the brand logo when an avatar is missing or fails to load.
- custom identity navigation measures `statusBarHeight` and the WeChat menu-button rectangle at runtime; it must reserve the capsule area instead of relying only on CSS safe-area insets or fixed side widths.

## Quality gate

Run `pnpm test:miniprogram` and `git diff --check`. Static tests must:

- discover components through JSON files whose `component` field is `true`, rather than assuming every vendor JavaScript utility is a component bundle;
- parse upstream ESM utilities as modules while leaving the pinned vendor source unchanged;
- resolve every page/component registration and WXML event handler;
- assert the source commit/license, theme import/mapping, no VIP directories, all-page wrapper use, adapter payloads, icon contracts and package ignores;
- cover all 26 registered pages, including the optional profile-edit page, without weakening the wrapper/event/asset checks;
- calculate the upload-source size after `packOptions.ignore`, target less than 1.4 MiB for design changes, and always keep the main package below 2 MiB.

Finally compile and inspect the project in WeChat Developer Tools and perform the release-checklist device flow; Node static tests do not emulate the WXML/WXSS renderer.
