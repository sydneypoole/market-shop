# 新增地址请求体反序列化根因

## 代码对照

- 小程序当前新增地址 body 省略 `version`。
- 后端 `CustomerAddressController.SaveAddressRequest` 声明 `int version`。
- `POST /api/v1/addresses` 虽然业务上固定使用 expectedVersion `0`，但 JSON 必须先成功构造完整 record。

## 本地复现

使用项目 Spring Boot 4.1 依赖中的 `tools.jackson.databind.ObjectMapper` 直接反序列化该 record：

- 省略 `version`：`MismatchedInputException: Cannot map null into type int`。
- 显式发送 `"version": 0`：成功得到 `SaveAddressRequest(..., version=0)`。

## 结论

客户端恢复新增地址的 `version: 0` 是最小修复；邮编仍可省略，因为 `postalCode` 是可空 `String`。后端无需放宽 primitive/creator 反序列化规则。
