# Kingdom 3D vision Java 后端协作规则

## 仓库范围

- 本仓库只包含 Java 21 + Spring Boot 后端；前端和平台级编排位于 `NoahWorld/factory-digital-twin-platform`。
- `src/main/resources/contracts/` 是与前端共享契约的已生成快照。修改契约时必须在平台仓库重新生成，并同步更新这里的文件与测试。
- 场景和 `model-3d` 的 `preventBottomView` 是默认开启的正式布尔配置。Java 从生成契约补齐旧文档中缺失的字段，保留显式 `false`，拒绝 `null` 和非布尔值；新项目、场景读取、保存与资源 manifest 必须返回一致设置，不维护浏览器专用副本。
- PostgreSQL 迁移只放在 `src/main/resources/db/migration/`，由 Flyway 管理。
- 2D/3D 点击动作使用平台仓库的 `shared/twin-actions.ts` 生成契约，Java 在保存文档的同一事务中校验最终节点/实例状态、目标引用及项目权限，失败必须回滚。不得保存任意脚本或绕过关联项目授权；对应回归为 `TwinActionsTest` 和平台仓库的 `pnpm backend:smoke:twin-actions`。

- 公共流体采用平台 `shared/fluids.ts` 同级 `scene.fluids` 契约，Java 保存于现有 settings JSONB 内部并在 API/manifest 中抽离；settings-only PATCH 保留流体，缺失旧字段读为 `[]`，显式 `null` 非法。流体整数组替换，与模型共用权限、事务、revision 和封面失效；公共 settings 拒绝嵌套 fluids。数量/路径/数值预算由生成 schema 与共享正反样例约束，修改后同步 `FluidContractTest`、`DocumentControllerTest` 并在平台运行 `pnpm backend:smoke:fluids`。

## 安全要求

- 不得提交 `.env`、密码、令牌、私钥、客户数据、备份、对象存储内容或 Maven 构建产物。
- 数据库、Valkey、S3 和初始化令牌必须通过环境变量注入；缺失必需配置时应明确失败，不得使用源码默认密钥。
- API、采集与任务失败必须保留请求或任务上下文，不得用空返回或假成功掩盖错误。
- 前端来源必须与 `ALLOWED_ORIGINS` 精确匹配（包括端口）；Vite 改用其他端口时，经授权修改平台仓库的本地白名单并重新创建 API 容器，禁止通配来源、伪造 Origin 或关闭校验。具体操作见 `README.md`。

## 验证

- 项目封面遵循 Flyway V3：只存经过校验的 960 × 540 PNG（最大 2 MiB），不生成概念 SVG。上传 `PUT /api/v1/projects/{id}/cover` 使用 `image/png` 原始字节，必须传 `sourceRevision` 与 `expectedCoverRevision`，在项目、文档和封面锁内验证编辑权限及双版本。成功上传和失效均递增封面版本；文档保存与被引用 3D 场景保存使封面 pending，改名不失效。引用场景变更不得递增 2D 文档版本。pending 可保留最后真实截图，状态与来源版本必须如实返回；无截图明确 404，不造占位图片。读取 PNG 和 ETag 304 必须先验证读权限，缓存保持 private。PNG 不得写入节点 JSON 或公开模型存储。
- PNG 校验必须有压缩文件大小、固定像素尺寸、块边界/CRC、有界解压和实际解码多层预算；拒绝 APNG、压缩元数据与尾随数据。任何校验/并发错误都应带明确错误码，不能静默替换图片。

- 提交前运行 `mvn -B verify`。
- 修改 API、数据库迁移、权限或共享契约时，需同时在平台仓库运行相应的集成与前端契约检查。
