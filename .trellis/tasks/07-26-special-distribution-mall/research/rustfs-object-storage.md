# RustFS 对象存储替换调研

日期：2026-07-26

## 结论

- 本地私有对象存储由 MinIO 替换为 RustFS，业务侧继续只依赖 `PrivateObjectStoragePort`。
- Java 基础设施适配器改用 AWS SDK for Java v2。RustFS 官方将其作为 Java 推荐客户端，并要求自定义 endpoint、`us-east-1` region 与 path-style URL。
- 本地 Docker 使用 `rustfs/rustfs:1.0.0-beta.11`，S3 API/Console 分别暴露 9000/9001，使用 `RUSTFS_ACCESS_KEY`、`RUSTFS_SECRET_KEY` 和独立 `rustfs-data` 数据卷。
- RustFS 容器以 UID/GID `10001:10001` 运行，Compose 增加一次性权限初始化服务。
- 旧 MinIO 卷不直接挂载给 RustFS；如旧环境已有凭证对象，应先通过 S3 客户端复制对象并核对对象数/摘要，再下线旧卷。

## 契约

```text
付款/售后用例
  -> PrivateObjectStoragePort
  -> S3PrivateObjectStorageAdapter
  -> AWS SDK v2 (path style + SigV4)
  -> RustFS private bucket
```

环境变量：

- `MARKET_SHOP_RUSTFS_ENDPOINT`
- `MARKET_SHOP_RUSTFS_ACCESS_KEY`
- `MARKET_SHOP_RUSTFS_SECRET_KEY`
- `MARKET_SHOP_RUSTFS_BUCKET`
- `MARKET_SHOP_RUSTFS_REGION`
- `MARKET_SHOP_RUSTFS_API_PORT`
- `MARKET_SHOP_RUSTFS_CONSOLE_PORT`

## 官方资料

- RustFS Java SDK 指南：https://docs.rustfs.com/developer/sdk/java
- RustFS Docker 安装：https://docs.rustfs.com/installation/docker/
- RustFS 官方 Compose：https://github.com/rustfs/rustfs/blob/main/docker-compose.yml
- RustFS Docker 镜像标签：https://hub.docker.com/r/rustfs/rustfs/tags
- AWS SDK for Java v2：https://github.com/aws/aws-sdk-java-v2
