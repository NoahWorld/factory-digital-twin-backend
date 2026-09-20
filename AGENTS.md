# Kingdom 3D vision Java 后端协作规则

## 仓库范围

- 本仓库只包含 Java 21 + Spring Boot 后端；前端和平台级编排位于 `NoahWorld/factory-digital-twin-platform`。
- `src/main/resources/contracts/` 是与前端共享契约的已生成快照。修改契约时必须在平台仓库重新生成，并同步更新这里的文件与测试。
- PostgreSQL 迁移只放在 `src/main/resources/db/migration/`，由 Flyway 管理。

## 安全要求

- 不得提交 `.env`、密码、令牌、私钥、客户数据、备份、对象存储内容或 Maven 构建产物。
- 数据库、Valkey、S3 和初始化令牌必须通过环境变量注入；缺失必需配置时应明确失败，不得使用源码默认密钥。
- API、采集与任务失败必须保留请求或任务上下文，不得用空返回或假成功掩盖错误。
- 前端来源必须与 `ALLOWED_ORIGINS` 精确匹配（包括端口）；Vite 改用其他端口时，经授权修改平台仓库的本地白名单并重新创建 API 容器，禁止通配来源、伪造 Origin 或关闭校验。具体操作见 `README.md`。

## 验证

- 提交前运行 `mvn -B verify`。
- 修改 API、数据库迁移、权限或共享契约时，需同时在平台仓库运行相应的集成与前端契约检查。
