# Java 后端：本地运行与交付边界

## 2026-09-27：独立仓库开发与场景扩展

本仓库 `main` 是 Java 后端修改、构建和契约生成目标；前端及共享 TS 定义仍在平台仓库 `main`。已对齐平台较新的流体、模块权限、点位驱动、内置图片、模型压缩和发布代码。平台中的 `apps/backend` 只保留历史参考，不再作为运行目标。

场景 API 增加同级 `decorations`、`roomAlarms`、`staticMap`，读取响应根返回 `sceneExtensionsVersion: 1`。三字段与原流体在既有 settings JSONB 中保存，使用同一个场景 revision 事务；省略保留、数组 `[]` 和地图 `null` 显式清空。无需为这三个字段新增数据库表。最终实例/资源/业务资产/指标引用、预算和权限由服务端校验；模型内部房间节点的存在与唯一性仍由前端目录及运行层复核。

每个成功返回的运行指标增量携带 `sourceId`、`timestamp`、`collectedAt`、`quality`，报警不会把另一慢源的时间当作火警时间。固定发布快照保留场景扩展，内置图片按不可变白名单 ID 使用；公开分享仍按原约定读取当前保存项目。操作与格式见平台的 `docs/scene-extensions.md`。

在平台执行 `pnpm backend:contracts` / `backend:contracts:check` 会默认写入本仓库；其他目录布局设置绝对路径 `TWIN_BACKEND_DIR`。在本仓库执行 `mvn -B verify`；用于启动或部署的 JAR 使用 `mvn -B clean verify`，清除已删除或改名的编译资源后重新打包。迁移资源测试会核对 classpath 中的 SQL 与源目录完全一致，并检查 Flyway 版本唯一；遇到重复迁移先修复构建产物，不修改数据库迁移历史。原生启动时从本仓库根运行，Meshopt worker 默认 `meshopt/worker.mjs`；可用 `TWIN_MESHOPT_WORKER` 显式指定，容器已设置 `/app/meshopt/worker.mjs`。宿主机同时运行 api/collector/worker 时要分配不同 `PORT`，容器中端口则相互隔离。

以下较早运行说明保留集成背景；当前已恢复的固定快照、分享和 Meshopt 能力不再属于后文的旧待实现范围。自动 LOD、上游新协议、凭据解析、可移植离线发布包等不在本轮四项场景能力范围内。

> 本仓库是 Kingdom 3D vision Java 后端的独立代码仓库。可在仓库根目录执行 `mvn -B verify` 完成编译和测试。文中 `pnpm backend:*`、前端联调及本地完整基础设施命令属于[平台仓库](https://github.com/NoahWorld/factory-digital-twin-platform)的集成流程，不包含在本仓库中。

2026-09-17 首版，现已从平台单仓库中的 `apps/backend` 独立发布；Java 21 + Spring Boot 3.5.16 + PostgreSQL 17 + Valkey 8.1 + S3 兼容对象存储。现有 Cloudflare Worker 保留，未切换线上流量或搬迁 D1/R2 数据。

一个模块化 Spring Boot 应用，按 `TWIN_MODE=api|collector|worker` 启动三个独立进程，统一代码、版本和迁移。API 负责授权、配置和 WebSocket；collector 集中采集 REST；worker 执行持久化资源检查任务。此部署没有 Kubernetes、服务注册中心或跨服务分布式事务。

## 本机入口

| 服务 | 地址/端口 | 说明 |
| --- | --- | --- |
| React 前端 | http://127.0.0.1:5173/#/projects | `pnpm dev:web:java` 启动，同源代理 `/api` 与 WS |
| Java API | http://127.0.0.1:18080/health | `/api/v1`，健康检查验证 PostgreSQL、Valkey、S3 |
| PostgreSQL | 127.0.0.1:15432 | 数据库 `factory_twin`，本地用户 `twin` |
| Valkey | 127.0.0.1:16379 | 最新状态、短期事件流；需要密码 |
| S3 | http://127.0.0.1:18333 | 私有桶 `factory-twin`，SeaweedFS 4.47 mini |

数据库、对象存储、缓存都绑定宿主机回环地址。密码、S3 密钥、初始化令牌在 `deploy/local/.env`；随机管理员密码在 `deploy/local/.local/admin.json`，登录名为 `admin`。两个文件权限为 0600，已加入 Git 忽略；没有源码默认密码。

前端实际地址须与 `ALLOWED_ORIGINS` 精确匹配（包括端口）。如果 5173 被其他项目占用，Vite 可能改用 5174，需要在平台仓库的 `deploy/local/.env` 中显式加入实际来源（例如 `http://127.0.0.1:5174`），不要使用通配来源。修改后在平台仓库根目录执行 `docker compose --env-file deploy/local/.env -f deploy/local/compose.yml up -d --no-deps api` 重新创建 API 容器，单纯 `restart` 不会载入新的环境变量。本地白名单和凭据不随源码提交。

S3 适配器与供应商解耦，本地选择 SeaweedFS，不要求客户绑定这个产品。接其他 S3 实现时必须实测签名、CORS、分片、Range、过期清理与恢复流程。`S3_ENDPOINT` 是容器可访问的内部地址；`S3_PUBLIC_ENDPOINT` 必须能被浏览器访问且与签名 Host/path 一致。

## 从干净环境启动

前置条件：Docker/Compose、Node 22.13+、pnpm；只使用容器构建时无需宿主机 JDK/Maven。命令均在仓库根目录执行。

```bash
pnpm install
pnpm backend:init
pnpm backend:contracts:check
pnpm backend:up
pnpm backend:bootstrap
pnpm dev:web:java
```

`backend:init` 生成随机密钥，已有 `.env` 会保留。首次启动 Flyway 自动建表；等 `/health` 返回 200 后执行 bootstrap。初始化仅在没有用户时允许，脚本将随机账号信息保存在本地私密文件。Java 新密码要求 12–256 字符，PBKDF2；历史 Worker 的临时 6 字符要求不适用于这里。

本机已安装 OpenJDK 21 与 Maven，并使用宿主机构建产物运行容器：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
pnpm backend:verify
pnpm backend:up:prebuilt
```

`backend:verify` 包含编译、测试和打包。当前 `prebuilt` 交付产物来自本独立仓库的 `target/backend-0.1.0.jar`，修改 Java 或迁移资源后，先在本仓库执行 `mvn -B clean verify` 再重建容器；迁移文件改名后仅执行增量构建可能把历史 SQL 一起打包，导致 Flyway 拒绝启动。本机初始化遇到仓库直连超时，下载阶段使用了本机代理及临时 Maven settings；该网络配置未写入项目。离线交付应预先导出镜像和前端产物，不能依赖现场联网下载。

```bash
pnpm backend:logs
pnpm backend:down
pnpm backend:up:prebuilt
```

`down` 保留三个数据卷；**`down -v` 会删除持久数据，不是日常停止命令**。OrbStack/Docker 引擎需运行。前端开发服务器是宿主机进程，关闭终端后需重启。Compose 只管理本项目，不管理已有 Node-RED 等容器。

## 已实现的接口与数据流

- 身份：首管理员初始化、账号/邮箱登录、持久会话、退出；管理员通过 `GET/POST /api/v1/users` 和 `GET/PUT/DELETE /api/v1/users/{id}` 创建、查看、编辑、删除账号，通过 `POST /api/v1/users/{id}/restore` 恢复账号。编辑可修改资料、全局角色、模块授权，并可重设密码、撤销原会话。删除是停用账号并撤销会话，保留项目和审计记录；当前管理员不能删除自己或移除自己的管理员角色，最后一个启用的管理员不能被移除。全局角色与项目 owner/editor/viewer 分开校验。管理员也可通过 `PATCH /api/v1/users/{id}/modules` 分别授权 `2d`、`3d`，或授予空数组；平台管理员固定拥有两个模块。模块授权是项目类型的访问前提，项目成员权限继续决定具体项目的操作。V6 迁移保留既有账号的两个模块，新账号需在 `POST /api/v1/users` 中明确提交 `modules`；已有会话下一次请求即使用新授权。会话从数据库取得租户，业务请求不能通过自行填写 tenantId 越权。登录可显式指定 `tenant`；默认 `local`。
- 项目、2D 画布、独立 3D 场景、资产、数据源配置与指标绑定；沿用 `/api/v1`、Cookie、`error/message/requestId` 和 `expectedRevision`。并发保存返回 409；原有错误码 `unauthenticated` 保持一致；文档与资源时间字段输出带 `Z` 的 ISO 8601 UTC 时间，浏览器按本地时区展示。
- 2D 与 3D 项目封面只接受浏览器按已保存项目真实渲染的 960 × 540 PNG（最大 2 MiB）。Flyway V3 删除历史概念 SVG，新项目和迁移项目均为 `pending`，不再由后端绘制占位图。`PUT /api/v1/projects/{id}/cover?sourceRevision=N&expectedCoverRevision=N` 接收 `image/png` 原始字节，要求项目编辑权限，在项目、文档和封面锁内校验双版本；冲突分别返回 `409 revision_conflict` / `409 cover_revision_conflict`。PNG 必须通过块边界、CRC、固定尺寸、有界像素解压和实际解码检查，不接受 APNG 或压缩元数据。
- 项目接口返回 `coverStatus`、`coverUrl`、`documentRevision`、`coverSourceRevision`、`coverRevision`。新建封面版本为 1；成功上传、文档保存、被引用 3D 场景保存均使对应封面版本递增。保存使封面 `pending`，3D 变更同时使同租户 `scene-3d` 节点引用它的 2D 封面失效，但不修改引用项目文档版本；改名不重新截图。`pending` 可保留最后一张真实 PNG，状态和来源版本明确表示它等待更新；从未截图时 URL 为 null，`GET /api/v1/projects/{id}/cover.png` 返回 `404 project_cover_pending`。图片读取和 304 均先校验项目读权限，使用 `private, no-cache` 与 ETag；PNG 独立保存于 `project_covers`，不得放入节点 JSON 或公共模型存储。
- 配置按节点/实例规范化保存；`GET /projects/{id}/manifest` 和 `GET /projects/{id}/document-items?revision=…&offset=…&limit=…` 提供版本绑定的清单和分页（最多 200 项）。原有整份文档接口继续兼容。
- 旧 `model-3d` 画布在保存时补齐缺失的实例、外观、灯光和动画配置，默认值由原 TypeScript 校验器导出到契约的 `default` 注解；不改写已有值，不修复 `null` 或类型错误。属性严格按节点类型校验，错误去重并记录到带请求 ID 的日志，避免把缺少 `modelInstances` 误报成 `animationSpeed` 不受支持。`backend:smoke` 验证旧模型保存/读取与前端解析结果一致。
- 模型、图片、视频/音频资源的受权访问与删除，文件签名检查、自包含 glTF/GLB 检查、SHA-256、私有 S3 与短期签名下载，支持 Range。短期签名链接在过期前具有持有者访问能力。
- 保留前端原始二进制上传接口；新分片接口 `POST /projects/{id}/uploads` 接收 `{kind,filename,byteSize}`，`POST /uploads/{resourceId}/parts/{number}` 取得 PUT 签名，`POST /uploads/{resourceId}/complete` 完成上传并返回 202。`GET /uploads/{resourceId}` 查看 uploading/processing/ready/failed。单片 5 MiB，完成时核对分片顺序及总字节数；后台检查通过才成为 ready。
- PostgreSQL 持久任务与采集租约，`SKIP LOCKED` 领取、租约过期处理和采集代次校验；后台进程重启不会依赖丢失的内存任务。普通上传中断后，超过五分钟仍未完成的资源会标记失败，可删除后重传。
- REST 由 collector 按数据源采集一次，旧前端 `runtime-state` 接口读取共享状态，不再因为展示用户增多而重复请求上游。JSON 字段映射生成统一资产指标；数据过期返回 stale，采集失败返回 offline，不返回伪造实时值。
- WebSocket `/api/v1/realtime`：Cookie 与 Origin 校验、项目授权、快照、增量、心跳、游标续传、游标过期重新同步、会话撤销与慢客户端断开。对外只发送映射后的资产指标，不广播上游原始 JSON。
- `/health`、管理员 `/api/v1/audit-events`、`/api/v1/capabilities`、带请求 ID 的 API 日志、采集/任务上下文日志。容器日志按大小轮转。

前端 TS 定义仍是配置字段来源。`scripts/backend-contracts.mjs` 从共享定义生成 JSON Schema、内置模型目录、尺寸与预算文件；`--check` 防止生成物过期。Java 另行执行授权、引用和预算检查。类型生成不等于完整语义等价，现有 TS 的全部特定组件校验仍需逐项迁移和增加正反样例。

## 实时连接例子

先通过同源登录取得 HttpOnly Cookie，再创建同源 WebSocket：

```js
const ws = new WebSocket(`ws://${location.host}/api/v1/realtime`);
ws.onopen = () => ws.send(JSON.stringify({type: 'subscribe', projectId}));
ws.onmessage = event => {
  const message = JSON.parse(event.data);
  // snapshot：替换项目运行态；source_update：合并资产指标并记住 cursor。
  // resync_required：丢弃旧增量状态，等待随后的 snapshot。
};
// 重连后发送 {type:'subscribe', projectId, cursor:lastAppliedCursor}。
```

生产 HTTPS 页面使用 `wss://`。消息具体样例与行为见 `scripts/backend-smoke.mjs`。单连接最多订阅 4 个项目；当前 API 进程最多 500 条连接，输入 8 KiB，发送缓冲有界。事件流是约 512 条的短期恢复窗口，不是历史数据库；Valkey 数据丢失/窗口过期时必须重新拉快照。断线恢复不代表跨系统恰好一次。

外部采集必须在 `.env` 中显式设置 `RUNTIME_ALLOWED_ORIGINS`（完整 scheme + host + port，多个用逗号分隔），默认空值拒绝全部采集。允许列表应只包含受控设备网关；应用检查不能替代网络出口防火墙和 DNS 管理。禁止重定向，超时最多 15 秒，响应最多 256 KiB。

本机为了集成测试已允许 `http://host.docker.internal:8790`，测试脚本只在测试期间启动明确的模拟数据源。它不是客户数据连接。删除此允许项并重建应用容器即可关闭该入口。

## 项目公开发布

Java 后端支持项目编辑者和管理员发布 2D 或 3D 项目。项目卡片的发布按钮生成 `#/share/{shareToken}` 链接；访问者打开链接无需登录，只能查看当前已保存的项目。本地 `127.0.0.1` 链接仅本机可用；要分享给其他人，须将前端和 Java API 部署到他们可访问的同站点地址。发布时检查发布者对根项目及所有关联项目的编辑权限、模型/图片/媒体引用资源是否 `ready`，并限制关联项目不超过 32 个。关联范围在发布时确定；新增跨项目引用后要重新发布才能加入公开范围，重新发布会生成新链接并立即废止旧链接。

登录会话接口：`GET /api/v1/projects/{id}/publication` 查看状态，`POST` 发布或重新发布，`DELETE` 取消发布。公开读取接口：`GET /api/v1/publications?share={shareToken}`、`GET /api/v1/publications/projects?share=…`、`GET /api/v1/publications/projects/{id}/canvas|scene?share=…`，以及同一路径下的 `assets`、`assets/{asset}/runtime-state`、`model|image|media-assets`、资源 `content`、`cover.png`、`twin-drive`。公开点位连接为 `/api/v1/twin-drive?projectId={id}&share={shareToken}`，仅允许订阅和心跳，控制命令返回 `403 publication_read_only`。公开接口每次重新验证令牌与范围，取消发布后新请求返回 `404 publication_not_found`，现有点位连接在下一次消息或推送时关闭。分享令牌应像访问凭据一样保管；任何拿到链接的人都能看到项目当前保存的内容及关联展示数据。已签出的 S3 下载 URL 在其最多 5 分钟有效期内仍可访问。

公开链接分享与生产交付验收是两个流程；模型映射、数据源连通性、字段阈值、性能和版本等交付检查仍按本仓库 `AGENTS.md` 执行。Cloudflare Worker 验证环境尚未实现公开发布，需使用 Java 后端。

## 接口驱动模型（V5）

`shared/twin-drive.ts` 是唯一字段契约。`GET/PUT /api/v1/projects/{id}/twin-drive` 使用独立 `twin_drive_documents` revision；保存 `{expectedRevision,config}`，读取 `{projectId,revision,config,editable}`。冲突返回 `409 twin_revision_conflict`。新配置只接受 `source:"api"`：先填写一个 REST 或 WebSocket 接口，测试读取字段，再把数值字段绑定到模型。`assetId` 可以为空，字段映射与平台业务资产编号解耦；仍保留实例/模型资源引用、原生动画排他、绑定层级及有界工程数值校验。驱动实例不能同时播放原生动画。

```js
connection: {
  protocol: 'rest', // 或 websocket
  url: '/api/v1/test-business/handling-cell/state',
  timestampPath: 'timestamp',
  intervalMs: 500, // REST 轮询间隔 200–60000 ms
  timeoutMs: 5000 // 请求/WS 无样本超时 500–30000 ms
}
// 点位 sourcePath 如 agv.positionM 或 $.robot.angleDeg；工程范围由 min/max 定义。
// WebSocket 可填写 subscribeMessage（JSON 对象/数组文本），内置测试 WS 无需发送。
```

`POST /api/v1/projects/{id}/twin-drive/test-source` 接受 `{connection}`，只允许项目编辑者/管理员，测试未保存的来源并返回 `{timestamp,fields:[{path,type,value}]}`；最多 128 个标量字段，字符串样例最多 256 字符，不保存完整响应。路径只允许最多 8 层字段/三位数组索引，拒绝表达式与原型字段。时间字段必须是 ISO-8601，采集值必须为数值或布尔值且在点位工程范围内。

后端实际请求 REST/WS，上游地址不会交给浏览器。外部 HTTP(S) 来源必须与 `RUNTIME_ALLOWED_ORIGINS` 精确匹配；WS/WSS 按对应 HTTP(S) origin 校验。禁止 URL 用户名密码、凭据 query、片段、重定向及任意内部代理。相对地址只接受下面两个固定测试路径，并固定连接服务器自身 `127.0.0.1:{server.port}`，不信任请求 Host。后端不透传浏览器 Cookie、Origin 或其他认证头。只读/公开文档对外部来源返回 `url:"",redacted:true` 并移除订阅报文；两个公开合成测试路径保留用于清楚标识测试来源。只读脱敏配置不能保存或测试。

公开合成测试接口无需登录，始终由后端时钟生成同一搬运单元状态：

- `GET /api/v1/test-business/handling-cell/state`：轮询读取。
- `WS /api/v1/test-business/handling-cell/live`：每 200 ms 推送。浏览器 Origin 仍必须与 `ALLOWED_ORIGINS` 精确匹配；内部采集仅在实际远端为 loopback 时允许固定自身 Origin。

两种接口输出相同的真实后端时钟状态，200 ms 采样。业务示例是 64 秒“送检与回收”：载车到站停稳，机械臂夹持抬升并放到检验台，臂收回后空车返程；检验后空车接件，机械臂取回装车并收回，载车带同一个工件返回起点。工件一直存在，AGV→夹爪→检验台→夹爪→AGV 的归属交接都在停稳接触位置发生，循环不瞬移补料。

- `agv.positionM`：从运输起点沿 +Z 的位移（0–4 m）；`velocityMps`：有符号实际速度；`wheelAngleDeg`：按车轮半径生成的无滑动累计角，不取模。
- `robot.baseYawDeg/shoulderDeg/elbowDeg/wristDeg`：真实转台及肩、肘、腕角度；`robot.tcp.{xM,yM,zM}`：夹持中心。腕关节补偿保持夹爪朝下。
- `gripper.openingM`：夹口距离；`cargo.{xM,yM,zM,yawDeg,attachment}`：唯一工件的世界位置、朝向和承载方。
- `cycle.phaseCode/phase/label`：明确当前工序，22 阶段由共享几何契约提供；`durationMs/elapsedMs`：周期及当前时间。
- `timestamp/sequence/scenario/geometryVersion`：采样时间、序号、来源与几何版本。旧 `robot.angleDeg` 保留 -60–60 度兼容别名，新示例绑定真实关节字段。

GLB 和后端共同使用平台 `shared/handling-cell-geometry.json`（后端生成副本 `contracts/handling-cell-geometry.json`），禁止各自维护尺寸、轴心或夹持偏移。轨迹采用平滑起停、先抬升再转移、垂直接近表面的路径；关节角由几何 IK 计算，浏览器不计算运动或补料。这些是无实际业务指向的合成读源，没有租户/客户内容；关闭浏览器仍继续推进。替换真实业务接口时修改来源和字段映射即可，平台不会发送设备命令。

API 模式的独立 `twinSourceTaskScheduler` 每 100 ms 组织采集，每秒按数据库保存的 tenant/project 发现启用的 API 配置；实际网络工作使用 8 个有界线程、128 排队预算，最多 128 个启用项目。REST/WS 响应最多 256 KiB、严格单个 JSON 对象、配置超时；WS 实际收样、原生心跳并按 500 ms 起步、最多 30 秒重试。每次尝试有 generation 栅栏，旧连接回调不能覆盖新观察。多 API 可重复只读采集，不积分或控制设备，也无需模拟写租约。

浏览器仍连接同源 Cookie 网关 `/api/v1/twin-drive?projectId=…`（公开预览增加 `share`），持续校验精确 Origin、会话/分享及项目读权限。收到 hello 后订阅当前 revision；API 模式没有 topic：

```js
ws.send(JSON.stringify({type:'subscribe', expectedRevision, topics:[]}));
// subscribed 确认后接收 snapshot；config_changed 后重新读取文档并订阅新 revision。
// 每 10 秒发送 {type:'ping'}；45 秒无客户端心跳关闭。
```

快照 `source:"api"`、状态 idle/running/error、全量点位实际值/时间/质量。初始 idle 没有样本；缺字段、越界、源时间/序号回退、断线、超时明确进入 error，附安全错误码和 retryCount，保留最后实际值且递增快照序号。日志包含租户、项目、revision、协议、脱敏来源 origin、根异常与重试次数，不记录订阅内容。断线或陈旧时浏览器冻结模型，不外推运动；成功的新实际样本恢复运行。新 revision 作废旧样本。

旧 `source:"simulator"` 文档和用户数据仍可读取用于迁移；旧模拟调度/命令已移除，保存旧配置或调用旧运行接口返回 `410 legacy_simulation_removed`。新 API 配置不接受 simulation、topic 或顺控 procedures。碰撞只作为浏览器配置盒的重叠事件，不是 PLC 安全联锁。

预算：配置 512 KiB、点位/绑定各 128、碰撞盒 64、碰撞规则 128、姿态采样每绑定 64。网关每 API 最多 128 条连接、输入 32 KiB、每连接每秒 40 条消息、发送缓冲 512 KiB/5 秒；测试源 WS 最多 128 连接、输入 8 KiB、发送缓冲 64 KiB/5 秒、原生 ping 15 秒/Pong 超时 45 秒。上限不代表压测吞吐承诺。

回归：`ApiMotionSourcesTest`、`TestBusinessTest`、`TestBusinessNetworkTest` 验证实际 HTTP/WS 每个反馈字段与同一时钟一致、整周期 FK/夹持接触/车轮方向/平滑边界、响应预算、来源权限、无观察者采集、字段错误/序号回退和旧回调栅栏；`TestBusinessTest` 同时导出忽略的 `target/handling-cell-states.json`（20 ms 连续实际轨迹）供独立 GLB 验收；`TwinDriveDocumentsTest`、`TwinDriveRuntimeTest`、`TwinDriveWebSocketTest` 验证共享契约、引用/动画冲突、版本订阅、权限撤销与旧模拟退出。

## 公共流体配置

独立 3D 场景通过同级 `scene.fluids` 保存公共流体配置，类型来自平台仓库的 `shared/fluids.ts`。`gas`、`liquid`、`molten` 都支持 `stream` / `diffuse`、颜色、空间路径、正反流向及播放设置；`GET /projects/{id}/scene` 与 manifest 返回相同配置。`PATCH /projects/{id}/scene` 可仅提交 `{expectedRevision, fluids}`，整数组替换，显式 `[]` 删除全部流体，省略则保留。流体与模型共用场景权限、事务及 revision，保存会使封面失效。

每场景最多 32 条，每条 2–64 个路径点；坐标范围 ±10000，半径 0.01–20，速度 0.01–30，扩散量 0–10，透明度 0.05–1。严格拒绝未知字段、重复 ID、相邻重复点、非有限数字、无效枚举以及显式 `null`；未完成路径只属于编辑器草稿。旧文档仅缺失该字段时读为 `[]`。

Java 使用现有 `documents.settings` JSONB 内部保存流体，不新增 Flyway 迁移；公共 API 的 `settings` 仍不接受 `fluids` 嵌套，settings-only PATCH 必须保留已有流体。契约和指纹必须在平台仓库生成后同步，不能手改生成 schema。对应回归为 `FluidContractTest`、`DocumentControllerTest`，平台命令 `pnpm test:fluids` 与 `pnpm backend:smoke:fluids`；后者只操作并清理自己的临时项目和账号，不依赖采集器模拟端口。

## 验证与备份

```bash
pnpm backend:contracts:check
pnpm backend:verify
pnpm --filter @factory-twin/web check
pnpm --filter @factory-twin/web build
pnpm backend:smoke
pnpm backend:backup
pnpm backend:backup:verify deploy/local/.local/backups/实际目录
```

集成测试限定本机 18080，需要 8790 空闲，以及上述 Docker-to-host 白名单。它创建自己的临时项目、用户和租户，完成后删除这些测试数据，不清空已有项目。覆盖真实 8 套前端模板、版本竞争、清单分块、3D、采集故障/恢复、实时快照/续传、文件完整性/Range/分片、角色与租户隔离等 22 项检查；Java 单元测试另有 12 项。浏览器已验证登录、创建项目、加载模板和保存。

2D/3D 声明式交互另有独立真 API 测试：在平台仓库运行 `pnpm backend:smoke:twin-actions`。它限定本机 18080，不启动模拟源或占用 8790，验证动作持久化、引用完整性、事务原子性和权限，默认清理自己的临时项目与账号；`--keep-fixture` 仅在成功后保留供浏览器验收，结束后执行 `pnpm backend:smoke:twin-actions --cleanup`。前置条件、临时记录与精确清理规则见平台仓库的 [联动交互验证](https://github.com/NoahWorld/factory-digital-twin-platform/blob/main/docs/linked-2d-3d-interactions.md#本地-java-api-冒烟测试)。

备份脚本会短暂停止本项目 API/collector/worker/storage，保留 PostgreSQL 运行，取得一致的数据库 dump 和对象存储数据目录，再启动原先运行的服务。备份位于忽略目录，包含密钥和管理员信息，需存入受控且加密的异机存储。Valkey 为可重建缓存，不纳入备份；恢复后重新采集并向客户端发新快照。

`backend:backup:verify` 校验 dump 哈希，在新建的临时数据库中执行完整 `pg_restore`，检查表与账号，再删除该临时库，不覆盖工作库。此检查不等于完整灾备演练：真实恢复还需在独立环境中用相同版本 SeaweedFS 恢复 `objects/` 数据卷、恢复配置、导入 PostgreSQL，再验证资源下载和项目引用。禁止对仍在提供写入的对象卷直接覆盖文件。

## 当前边界和扩容路径

这是一套可运行的本地首版，不等于已经验收的生产平台：

- 前端已通过同源代理连接 Java；旧页面仍按资产读取缓存。新的项目级 WebSocket、清单分块和直传接口已实现，但前端调用层尚未切换到它们。旧 Worker 的单图生成场景底座在 Java 返回 501，未伪造产物。
- 上游 WebSocket 数据源执行、`credentialRef` 密钥解析、模型优化/LOD、不可变发布包与版本回滚尚未实现。浏览器到服务器的 WebSocket 与上游数据源 WebSocket 是两件事。
- 数据包含 tenant_id、复合外键与应用授权，隔离已做回归；尚无租户开通/配额/计费、完整 RLS 或 SSO。当前托管可按客户部署独立实例。共享多租户生产部署需额外完成隔离审计与数据库最小权限；本地 Compose 的 `twin` 仍为开发用数据库所有者/超级用户。
- JSON 请求上限 2 MiB，补丁最多 100 项；模型 25 MiB、图片 8 MiB、视频 100 MiB、音频 30 MiB，沿用现有产品边界。3D 保留 128 实例/24 唯一模型/150 MiB/6000 网格/24 动画实例预算。画布存储保护上限 10,000 节点，不表示浏览器可流畅同时渲染这些节点。
- 项目、资产和数据源列表支持 `limit/offset/nextOffset`；旧调用不指定分页而超出 1000 项目/10000 条记录时明确返回 `pagination_required`，不会静默截断。前端分页界面与资源列表分页仍需完善；collector 单进程串行采集，API 目前按连接轮询共享流与授权。后续大负载需增加有界并发、按项目共享分发与查询批处理，并据真实模型/源速率/用户数量压测，不能把连接上限当吞吐承诺。
- 配置与对象存储之间没有分布式原子事务；失败显式报错并保留资源状态，删除中断可能需要重试及孤儿对象核查。对持久失败暂未提供运维重试 UI。
- 当前单机数据卷没有跨主机冗余；容器 restart 与备份不能提供主机故障自动切换。宿主机容量、磁盘使用/队列积压告警、指标看板与定期恢复演练尚需部署。

生产交付下一步：构建静态前端并置于统一 HTTPS 反向代理；精确配置可信 Origin、Secure Cookie、WSS 和文件域名；数据库使用专用迁移账号与最小权限应用账号；关闭数据库/缓存管理端口；固定镜像摘要、扫描依赖并做备份恢复验收。需要扩容时先将存储迁到独立服务，再按 API、collector、worker 角色增加副本；长连接重连与共享会话/游标避免依赖粘性会话。是否引入 Kubernetes 取决于实际机器数量与运维能力。
