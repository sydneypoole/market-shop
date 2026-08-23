# 修复 FirstUI bottom popup 编译缺失

## Goal

修复微信开发者工具编译 `sku-sheet` 时报告 `WXML file not found: ./components/firstui/fui-bottom-popup/fui-bottom-popup.wxml`，确保 vendored FirstUI 嵌套组件不会被开发工具的未使用文件裁剪误删。

## What I already know

* `fui-bottom-popup.js/json/wxml/wxss` 四个文件均存在、可读、已被 Git 跟踪，并包含在当前 `HEAD`。
* `components/sku-sheet/sku-sheet.json` 使用标准绝对路径 `/components/firstui/fui-bottom-popup/fui-bottom-popup` 注册组件。
* 静态扫描确认项目所有 `usingComponents` 引用的四件套文件均存在。
* `project.private.config.json` 当前设置 `ignoreDevUnusedFiles=true`；`fui-bottom-popup` 只通过 `sku-sheet` 间接依赖，容易被开发工具的未使用文件分析错误裁剪。
* 微信开发者工具 CLI 已安装，但本机 IDE 服务端口关闭，因此自动 preview 未进入编译阶段。

## Requirements

* 保留 FirstUI vendored 组件和 `sku-sheet` 的标准引用路径。
* 关闭开发阶段未使用文件自动裁剪，确保嵌套自定义组件完整参与编译。
* 增加静态回归断言，防止该配置被重新开启。
* 不关闭 `lazyCodeLoading=requiredComponents`，不复制或改名组件文件。

## Acceptance Criteria

* [x] `project.private.config.json` 不再开启 `ignoreDevUnusedFiles`。
* [x] 静态测试验证所有 `usingComponents` 文件存在，并锁定未使用文件裁剪为关闭状态。
* [x] 小程序完整测试通过。
* [ ] 微信开发者工具清缓存后可重新编译；CLI 实测在服务端口开启时执行。

## Definition of Done

* Tests added/updated
* Miniprogram test suite green
* `git diff --check` green
* Manual IDE cache-clearing step documented in handoff

## Out of Scope

* 替换 FirstUI 组件库。
* 修改 SKU 弹层交互或视觉。
* 上传小程序体验版。

## Technical Notes

* Component registration: `miniprogram/components/sku-sheet/sku-sheet.json`
* Vendored component: `miniprogram/components/firstui/fui-bottom-popup/`
* DevTools config: `miniprogram/project.private.config.json`
* Static regression suite: `miniprogram/tests/static-project.test.mjs`

## Implementation Plan

1. 将 `ignoreDevUnusedFiles` 改为 `false`，避免间接引用组件被裁剪。
2. 扩展静态项目测试，断言项目配置与所有组件文件完整。
3. 运行小程序测试与差异检查。

## Verification

* `pnpm test:miniprogram`: 78/78 passed.
* `git diff --check`: passed.
* 保留 `lazyCodeLoading=requiredComponents`，未修改 `sku-sheet` 或 FirstUI vendored source。
* 待手动验证：在微信开发者工具执行“清缓存 -> 全部清除”后重新编译；如需 CLI preview，先在工具的安全设置开启服务端口。
