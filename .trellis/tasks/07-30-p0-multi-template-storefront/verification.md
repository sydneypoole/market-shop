# 验收记录

## 自动化质量门禁

- `mvn -f backend/pom.xml clean test package`：通过。
- `pnpm test`：商城 6、后台 8、容器契约 6，全部通过。
- `pnpm typecheck:web`：两个 Vue 应用通过。
- `pnpm build:web`：商城与后台生产构建通过。
- 默认和 `rustfs` profile 的 `docker compose config --quiet`：通过。
- RustFS 真实集成：上传、短时签名下载、内容校验、删除及删除后 404 全部通过。

## 空库与运行时

- 使用隔离 Compose 项目和全新 MySQL 卷执行 Flyway V1–V8，版本 8 成功。
- 空库存在 `EDITORIAL`、`VIBRANT`、`MINIMAL` 三套模板，初始仅 `EDITORIAL` 生效。
- `scripts/runtime-smoke.sh` 验证两个 SPA、readiness、动态模板、多规格详情、分类、内容、请求 ID 和微信未配置安全关闭。
- 后台实际保存模板后版本从 0 递增到 1；发布后递增到 2，并原子替换活动模板。
- 保存和发布均生成带 request ID 的不可变管理员审计记录。

## 响应式视觉

- 三套模板分别在 1440、1024、390、360 像素宽度检查，均无文档级横向溢出。
- H5 均保留底部商城导航。
- 后台设计器配置栏为 360 像素，预览画布位于其右侧；H5 预览画布为 390 像素。
- 修复后台裸 `aside` / 后代 `main` 全局选择器对模板设计器和预览的样式污染，并增加静态回归测试。
