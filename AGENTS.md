# Kingdom 3D vision Java 后端协作规则

## 仓库范围

- 本仓库只包含 Java 21 + Spring Boot 后端；前端和平台级编排位于 `NoahWorld/factory-digital-twin-platform`。
- `src/main/resources/contracts/` 是与前端共享契约的已生成快照。修改契约时必须在平台仓库重新生成，并同步更新这里的文件与测试。
- 行业模板图片使用平台 `shared/builtin-images.ts` 生成的 `builtin-images.json` 精确白名单，包含 ID、版本、路径、字节数与 SHA-256。项目只存资源 ID；未知 ID、资源类型不匹配必须报错，内置资源不可删除。静态图片由平台前端分发，不能将任意 URL 当作内置资源；公开发布只允许读取当前文档引用的资源。
- 场景和 `model-3d` 的 `preventBottomView` 是默认开启的正式布尔配置。Java 从生成契约补齐旧文档中缺失的字段，保留显式 `false`，拒绝 `null` 和非布尔值；新项目、场景读取、保存与资源 manifest 必须返回一致设置，不维护浏览器专用副本。
- PostgreSQL 迁移只放在 `src/main/resources/db/migration/`，由 Flyway 管理。
- 2D/3D 点击动作使用平台仓库的 `shared/twin-actions.ts` 生成契约，Java 在保存文档的同一事务中校验最终节点/实例状态、目标引用及项目权限，失败必须回滚。不得保存任意脚本或绕过关联项目授权；对应回归为 `TwinActionsTest` 和平台仓库的 `pnpm backend:smoke:twin-actions`。
- 数据驱动采用平台 `shared/twin-drive.ts` 唯一契约，Flyway V4 独立存储 `twin_drive_documents`。`GET/PUT /api/v1/projects/{id}/twin-drive` 使用独立 revision，保存校验业务资产、实例/模型资源引用、原生动画排他及有界工程数值；场景删除/换模型和业务资产 ID 重命名必须保护现存绑定，不自动改绑。
- `@Transactional` 服务通过 Spring CGLIB 代理调用；调用方不得直接读取被代理服务的实例字段，应使用构造注入依赖或服务方法。控制器首次 GET 和模拟命令回归必须覆盖真实 CGLIB 代理，不能只用未代理的手工实例测试。
- `/api/v1/twin-drive?projectId=…` 是与遥测 `/realtime` 分离的 Cookie + 精确 Origin 通道，持续复核会话与读权限。点位 topic 为唯一精确标识，收到 hello 后发送带 expectedRevision 的完整 topic 集合，subscribed 确认后才发送有 topic 配置的快照；未知/重复/通配符/部分订阅明确拒绝。config_changed 使订阅失效，必须加载新配置后重订阅，不跨项目路由。订阅、自动快照及自动模式命令拒绝在 PostgreSQL 项目锁内读取，不获取 Redis 积分租约；观察者不得与生产调度争抢写租约。手动操作取得写租约后仍须重查模式与版本。
- `simulation.enabled` 是用户明确保存的后台自动源配置，必须有完整 topic、模型绑定和有效 procedureId。仅 API 模式的独立 100 ms 调度器运行，数据库发现每秒一次且最多 128 个自动项目；无浏览器/订阅/命令仍持续运行，读取文档/封面/快照/订阅不初始化或积分自动源。顺控仅实际到位推进，repeat 保留实际值循环目标，不能 reset 瞬移或按超时切步骤。自动模式拒绝所有手动命令；旧手动配置保留 reset/set/move/pause 等编辑权限命令。浏览器只能由实际值驱动，断线冻结本地显示，重连使用最新值，不外推运动。
- 模拟运行态使用 Valkey 同槽 project key、带 token 租约与 Lua 写入栅栏，多个 API 不得重复积分；调度器只使用数据库保存的 tenant/project 配对，不合成管理员。真正超过一秒未调度的自动源进入 error，保留实际值，要求重新保存配置，不静默恢复；浏览器全关闭不停止后台源。新配置版本清空旧样本，自动配置从 initialValue 重启；Valkey 缓存丢失也从明确保存的配置重新初始化。命令有配置版本、最近 128 条有界去重、project 20/s 限速和审计，不能宣称跨缓存丢失/跨存储事务的永久恰好一次。碰撞为配置盒的浏览器重叠事件，不是真实 PLC 安全联锁；topic 不代表已接入 MQTT 或上游 PLC。回归包含 `TwinDriveDocumentsTest`、`TwinDriveEngineTest`、`TwinDriveRuntimeTest`、`TwinDriveWebSocketTest`；协议与预算见 README。

- 公共流体采用平台 `shared/fluids.ts` 同级 `scene.fluids` 契约，Java 保存于现有 settings JSONB 内部并在 API/manifest 中抽离；settings-only PATCH 保留流体，缺失旧字段读为 `[]`，显式 `null` 非法。流体整数组替换，与模型共用权限、事务、revision 和封面失效；公共 settings 拒绝嵌套 fluids。数量/路径/数值预算由生成 schema 与共享正反样例约束，修改后同步 `FluidContractTest`、`DocumentControllerTest` 并在平台运行 `pnpm backend:smoke:fluids`。

## 安全要求

- 账号具有独立 `2d`/`3d` 模块授权；V5 保留既有账号两个模块，新账号必须明确指定授权。平台管理员固定拥有两个模块，其他账号的模块授权与项目成员权限共同决定访问范围，每次请求读取数据库授权。管理接口支持编辑资料/角色/模块、重设密码并撤销会话、停用及恢复；不能删除当前管理员自己、移除自身管理员角色或移除最后一个启用的管理员。跨项目引用和点击动作也要校验目标模块权限，回归见 `ModuleAccessTest`。
- V6 支持编辑者或管理员公开发布项目：每次匿名读取与只读点位推送都重新校验分享令牌和发布范围（最多 32 个关联项目），发布者必须对所有项目有编辑权限。取消或重新发布立即废止旧令牌；已签出的对象存储 URL 最多仍可用 5 分钟。公开点位连接只允许订阅和心跳，控制命令必须拒绝。接口细节见 README，回归见 `PublicationsTest`、`TwinDriveWebSocketTest`；其他业务接口仍要求登录。
- 不得提交 `.env`、密码、令牌、私钥、客户数据、备份、对象存储内容或 Maven 构建产物。
- 数据库、Valkey、S3 和初始化令牌必须通过环境变量注入；缺失必需配置时应明确失败，不得使用源码默认密钥。
- API、采集与任务失败必须保留请求或任务上下文，不得用空返回或假成功掩盖错误。
- 前端来源必须与 `ALLOWED_ORIGINS` 精确匹配（包括端口）；Vite 改用其他端口时，经授权修改平台仓库的本地白名单并重新创建 API 容器，禁止通配来源、伪造 Origin 或关闭校验。具体操作见 `README.md`。

## 验证

- 项目封面遵循 Flyway V3：只存经过校验的 960 × 540 PNG（最大 2 MiB），不生成概念 SVG。上传 `PUT /api/v1/projects/{id}/cover` 使用 `image/png` 原始字节，必须传 `sourceRevision` 与 `expectedCoverRevision`，在项目、文档和封面锁内验证编辑权限及双版本。成功上传和失效均递增封面版本；文档保存与被引用 3D 场景保存使封面 pending，改名不失效。引用场景变更不得递增 2D 文档版本。pending 可保留最后真实截图，状态与来源版本必须如实返回；无截图明确 404，不造占位图片。读取 PNG 和 ETag 304 必须先验证读权限，缓存保持 private。PNG 不得写入节点 JSON 或公开模型存储。
- PNG 校验必须有压缩文件大小、固定像素尺寸、块边界/CRC、有界解压和实际解码多层预算；拒绝 APNG、压缩元数据与尾随数据。任何校验/并发错误都应带明确错误码，不能静默替换图片。

- 提交前运行 `mvn -B verify`。
- 修改 API、数据库迁移、权限或共享契约时，需同时在平台仓库运行相应的集成与前端契约检查。
