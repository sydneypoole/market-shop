# FirstUI vendored source

- Project: FirstUI-weixin (public edition)
- Upstream: https://github.com/FirstUI/FirstUI-weixin
- Version: V2.4.0
- Commit: `fa7863720afcf591aaf3ba6de29c42a88c6dde80`
- License: Apache License 2.0 (see `LICENSE`)
- Imported: 2026-08-11

Only the open-source components used by the native miniprogram are included. No VIP or npm-only source is present. The files inside each `fui-*` directory and `fui-theme/fui-theme.wxss` are copied unchanged from the pinned commit. Project-specific wrappers and brand overrides live outside this directory.

Vendored component set: `fui-badge`, `fui-bottom-popup`, `fui-button`, `fui-empty`, `fui-icon`, `fui-input-number`, `fui-list-cell`, `fui-loading`, `fui-loadmore`, and `fui-tag`.

The eight native tab bar PNG files in `assets/tab/` are derived from the
pinned `fui-icon` outline/fill glyphs (`home`, `classify`, `cart`, and `my`).
They are recolored to the app tab colors and optically centered on an 81 x 81
transparent canvas by `scripts/generate-miniprogram-tab-icons.py`. Matching
design copies live in `docs/design/miniprogram/icons/`.
