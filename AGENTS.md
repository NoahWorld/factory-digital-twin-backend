# Kingdom 3D vision Java 后端协作规则

## 仓库范围

- 当前 Owner 要求在本仓库 `main` 与平台前端 `main` 配套开发；本轮既有能力同步及四项场景功能已完成本地验证，Owner 已授权提交并推送到两个仓库的 `main`，不包含外部部署。原平台 `newpower` 目标保持暂停。
- `decorations`、`roomAlarms`、`staticMap` 通过原 scene 文档事务保存；settings-only 保留它们及 fluids，省略不清空。报警使用业务 assetId/metricKey 和实例/资源引用，每个 metric 的时间与 sourceId 必须对应，不能取其它源的聚合时间。几何预算及结构由平台共享定义生成，Java补语义与最终引用检查。
- 固定版本捕获应识别内置模型和内置图片的不可变 ID，不查询虚构的上传资源；单数公开分享与复数固定版本保持各自既有语义。

- 本仓库只包含 Java 21 + Spring Boot 后端；前端和平台级编排位于 `NoahWorld/factory-digital-twin-platform`。
- `src/main/resources/contracts/` 是与前端共享契约的已生成快照。修改契约时必须在平台仓库重新生成，并同步更新这里的文件与测试。
- 行业模板图片使用平台 `shared/builtin-images.ts` 生成的 `builtin-images.json` 精确白名单，包含 ID、版本、路径、字节数与 SHA-256。项目只存资源 ID；未知 ID、资源类型不匹配必须报错，内置资源不可删除。静态图片由平台前端分发，不能将任意 URL 当作内置资源；公开发布只允许读取当前文档引用的资源。
- 场景和 `model-3d` 的 `preventBottomView` 是默认开启的正式布尔配置。Java 从生成契约补齐旧文档中缺失的字段，保留显式 `false`，拒绝 `null` 和非布尔值；新项目、场景读取、保存与资源 manifest 必须返回一致设置，不维护浏览器专用副本。
- PostgreSQL 迁移只放在 `src/main/resources/db/migration/`，由 Flyway 管理。
- 2D/3D 点击动作使用平台仓库的 `shared/twin-actions.ts` 生成契约，Java 在保存文档的同一事务中校验最终节点/实例状态、目标引用及项目权限，失败必须回滚。不得保存任意脚本或绕过关联项目授权；对应回归为 `TwinActionsTest` 和平台仓库的 `pnpm backend:smoke:twin-actions`。
- 接口驱动采用平台 `shared/twin-drive.ts` 唯一契约，Flyway V5 独立存储 `twin_drive_documents`。新保存仅 `source:"api"`：一个 REST/WS connection、全量点位 sourcePath 与模型绑定；API 的资产 ID 可空，仍校验实例/资源、原生动画排他及工程范围。旧 simulator 文档保留读取用于迁移，但保存/运行/命令明确 `410 legacy_simulation_removed`；不得重新引入模拟调度、initialValue 积分、顺控或 topic 配置。
- `@Transactional` 服务通过 Spring CGLIB 代理调用；调用方不得直接读取代理实例字段，使用构造注入或服务方法。控制器首次 GET 与源测试回归必须覆盖真实 CGLIB 代理。
- `ApiMotionSources` 在后端真实采集 REST/WS，禁止下发客户私有地址/订阅凭据到浏览器或透传浏览器 Cookie/Origin。外部来源使用 `RUNTIME_ALLOWED_ORIGINS` 精确白名单，WS 映射 HTTP(S) origin；相对来源只允许固定测试业务两个路径，并构造 loopback+server.port，不读取 Host。禁止 URL 凭据/片段/重定向；256 KiB 单 JSON 对象、时间/字段范围、超时和工作队列均有界。
- `/api/v1/twin-drive?projectId=…` 使用同源 Cookie + 精确 Origin，持续复核会话/分享与项目读权限。API 收到 hello 后以 expectedRevision/topics:[] 订阅，subscribed 后才能发送快照；config_changed 后重读并订阅。观察不请求网络、不积分、不获得项目写锁/Redis 租约；实际网络仅由独立 100 ms 采集调度+有界工作线程执行，保存租户/项目配对每秒发现。多 API 允许重复只读观察，不控制设备。
- 初始 idle 没有点位样本。源失败必须显露 error/error code/retryCount，递增快照序号并冻结最后实际值；时间/序号回退拒绝，不伪造运动或默认值。成功的新样本恢复，旧 WS 回调以采集 attempt 栅栏忽略。浏览器关闭不停止已保存来源，浏览器断线/陈旧冻结、不外推。日志保留租户/项目/revision/协议/安全 origin/根异常，不输出源 query 或订阅报文。
- `POST /projects/{id}/twin-drive/test-source` 仅编辑者测试未保存 connection，返回有界标量 fields/timestamp，不保存完整 JSON。只读/公开文档外部 URL 清空+redacted:true、移除订阅报文，固定公开测试路径保留以显示合成来源；脱敏配置不允许写回。
- 无租户公开测试业务读源 `/api/v1/test-business/handling-cell/state|live` 使用后端时钟、200 ms 采样。平台 `shared/handling-cell-geometry.json` 导出为后端 generated contract，统一 GLB 尺寸、轴心、TCP、夹指行程与阶段表；后端不可散落几何常量。64 秒“送检与回收”闭环只有一个工件：载车到站停稳→夹持抬升→检验台放件→臂收回→空车返回→检验→空车接件停稳→取回装车→臂收回→成品返回，不瞬移补料。关节 IK、腕垂直补偿、车轮无滑动有符号角、夹口与工件世界位置/朝向/所有权均由后端生成；C1路径先抬升再转台、接近后垂直下降，浏览器只映射反馈。`cycle.phaseCode/label` 来自统一22阶段表；保留旧 `agv.positionM` 与 `robot.angleDeg` 别名，不把旧角度当真实关节。仅合成数据，无客户内容/设备命令。WS仍精确 Origin+128连接+心跳+大小预算，固定自身内部 Origin 必须同时要求实际远端 loopback，不得伪装其他允许来源。
- `TestBusinessTest` 验证整周期 FK/TCP、停稳夹持、同一工件接触转移无瞬移、车轮方向与C1边界，并生成忽略的 `target/handling-cell-states.json` 20 ms真实轨迹，供独立 GLB 几何检查；不可由浏览器另造一套轨迹自证。
- 回归为 `ApiMotionSourcesTest`、`TestBusinessTest`、`TestBusinessNetworkTest`、`TwinDriveDocumentsTest`、`TwinDriveRuntimeTest`、`TwinDriveWebSocketTest`，协议与预算见 README。

- 公共流体采用平台 `shared/fluids.ts` 同级 `scene.fluids` 契约，Java 保存于现有 settings JSONB 内部并在 API/manifest 中抽离；settings-only PATCH 保留流体，缺失旧字段读为 `[]`，显式 `null` 非法。流体整数组替换，与模型共用权限、事务、revision 和封面失效；公共 settings 拒绝嵌套 fluids。数量/路径/数值预算由生成 schema 与共享正反样例约束，修改后同步 `FluidContractTest`、`DocumentControllerTest` 并在平台运行 `pnpm backend:smoke:fluids`。

## 安全要求

- 账号具有独立 `2d`/`3d` 模块授权；V6 保留既有账号两个模块，新账号必须明确指定授权。平台管理员固定拥有两个模块，其他账号的模块授权与项目成员权限共同决定访问范围，每次请求读取数据库授权。管理接口支持编辑资料/角色/模块、重设密码并撤销会话、停用及恢复；不能删除当前管理员自己、移除自身管理员角色或移除最后一个启用的管理员。跨项目引用和点击动作也要校验目标模块权限，回归见 `ModuleAccessTest`。
- V7 支持编辑者或管理员公开发布项目：每次匿名读取与只读点位推送都重新校验分享令牌和发布范围（最多 32 个关联项目），发布者必须对所有项目有编辑权限。取消或重新发布立即废止旧令牌；已签出的对象存储 URL 最多仍可用 5 分钟。公开点位连接只允许订阅和心跳，控制命令必须拒绝。接口细节见 README，回归见 `PublicSharesTest`、`TwinDriveWebSocketTest`；除明确公开的纯合成测试业务读源外，其他业务接口仍要求登录。
- 不得提交 `.env`、密码、令牌、私钥、客户数据、备份、对象存储内容或 Maven 构建产物。
- 数据库、Valkey、S3 和初始化令牌必须通过环境变量注入；缺失必需配置时应明确失败，不得使用源码默认密钥。
- API、采集与任务失败必须保留请求或任务上下文，不得用空返回或假成功掩盖错误。
- 前端来源必须与 `ALLOWED_ORIGINS` 精确匹配（包括端口）；Vite 改用其他端口时，经授权修改平台仓库的本地白名单并重新创建 API 容器，禁止通配来源、伪造 Origin 或关闭校验。具体操作见 `README.md`。

## 验证

- 项目封面遵循 Flyway V3：只存经过校验的 960 × 540 PNG（最大 2 MiB），不生成概念 SVG。上传 `PUT /api/v1/projects/{id}/cover` 使用 `image/png` 原始字节，必须传 `sourceRevision` 与 `expectedCoverRevision`，在项目、文档和封面锁内验证编辑权限及双版本。成功上传和失效均递增封面版本；文档保存与被引用 3D 场景保存使封面 pending，改名不失效。引用场景变更不得递增 2D 文档版本。pending 可保留最后真实截图，状态与来源版本必须如实返回；无截图明确 404，不造占位图片。读取 PNG 和 ETag 304 必须先验证读权限，缓存保持 private。PNG 不得写入节点 JSON 或公开模型存储。
- PNG 校验必须有压缩文件大小、固定像素尺寸、块边界/CRC、有界解压和实际解码多层预算；拒绝 APNG、压缩元数据与尾随数据。任何校验/并发错误都应带明确错误码，不能静默替换图片。

- 提交前运行 `mvn -B verify`；生成用于启动或部署的 JAR 必须运行 `mvn -B clean verify`，避免迁移资源改名后旧 SQL 留在 `target/classes` 并被再次打包。`MigrationResourcesTest` 校验应用 classpath 与源迁移文件集合、内容一致，且 Flyway 版本唯一；失败时清理生成输出后重新构建，不修改数据库迁移历史来绕过构建错误。
- 修改 API、数据库迁移、权限或共享契约时，需同时在平台仓库运行相应的集成与前端契约检查。
