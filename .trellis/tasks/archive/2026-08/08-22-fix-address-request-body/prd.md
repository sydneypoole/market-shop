# 修复新增收货地址请求体格式

## 目标

修复小程序调用 `POST /api/v1/addresses` 时返回 `REQUEST_BODY_INVALID`（请求体格式无效）的问题，使新增地址请求满足后端 `SaveAddressRequest` 的 JSON 反序列化契约。

## 根因

- 后端 `SaveAddressRequest.version` 是 primitive `int`。
- Spring Boot 4.1 使用的 Jackson 3 默认拒绝把缺失的 creator primitive 属性映射为 `null`。
- 小程序近期把新增地址 body 中的 `version: 0` 删除，导致 JSON 在进入 Bean Validation 和业务用例前反序列化失败。
- `postalCode` 是可空引用类型，空值继续省略即可。

## 要求

- 新增地址请求显式发送 `version: 0`。
- 编辑地址请求发送服务端读取到的整数版本号。
- 空邮编仍不发送，非空邮编保持 trim 后发送。
- 不修改接口路径、认证头或其他地址字段。
- 不覆盖 `miniprogram/pages/address/list.wxml` 的现有独立空行改动。

## 验收标准

1. 新增地址 body 包含完整必填字段、`defaultAddress` 和 `version: 0`。
2. 新增地址 body 不包含空 `postalCode`。
3. 编辑地址继续携带权威 `version`。
4. 小程序全量测试及 `git diff --check` 通过。

## 范围外

- 不改变后端地址 DTO 或并发版本语义。
- 不修改地址列表页面。

## 技术说明

- 目标代码：`miniprogram/pages/address/edit.js`。
- 回归测试：`miniprogram/tests/order-cart-usability-fixes.test.mjs`。
- 详细复现见 `research/root-cause.md`。
