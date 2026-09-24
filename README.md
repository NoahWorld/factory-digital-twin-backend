# Java 后端：本地运行与交付边界

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

`backend:verify` 包含编译、测试和打包。`prebuilt` 构建仅复制 `apps/backend/target/backend-0.1.0.jar`，修改 Java 后必须先重新打包。本机初始化遇到仓库直连超时，下载阶段使用了本机代理及临时 Maven settings；该网络配置未写入项目。离线交付应预先导出镜像和前端产物，不能依赖现场联网下载。

```bash
pnpm backend:logs
pnpm backend:down
pnpm backend:up:prebuilt
```

`down` 保留三个数据卷；**`down -v` 会删除持久数据，不是日常停止命令**。OrbStack/Docker 引擎需运行。前端开发服务器是宿主机进程，关闭终端后需重启。Compose 只管理本项目，不管理已有 Node-RED 等容器。

## 已实现的接口与数据流

- 身份：首管理员初始化、账号/邮箱登录、持久会话、退出；管理员通过 `GET/POST /api/v1/users` 和 `GET/PUT/DELETE /api/v1/users/{id}` 创建、查看、编辑、删除账号，通过 `POST /api/v1/users/{id}/restore` 恢复账号。编辑可修改资料、全局角色、模块授权，并可重设密码、撤销原会话。删除是停用账号并撤销会话，保留项目和审计记录；当前管理员不能删除自己或移除自己的管理员角色，最后一个启用的管理员不能被移除。全局角色与项目 owner/editor/viewer 分开校验。管理员也可通过 `PATCH /api/v1/users/{id}/modules` 分别授权 `2d`、`3d`，或授予空数组；平台管理员固定拥有两个模块。模块授权是项目类型的访问前提，项目成员权限继续决定具体项目的操作。V5 迁移保留既有账号的两个模块，新账号需在 `POST /api/v1/users` 中明确提交 `modules`；已有会话下一次请求即使用新授权。会话从数据库取得租户，业务请求不能通过自行填写 tenantId 越权。登录可显式指定 `tenant`；默认 `local`。
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

## 可配置点位驱动（V4）

`shared/twin-drive.ts` 是唯一字段契约。`GET/PUT /api/v1/projects/{id}/twin-drive` 使用独立 `twin_drive_documents` 版本，不混入场景设置或封面。保存提交 `{expectedRevision,config}`，读取返回 `{projectId,revision,config,editable}`；版本冲突返回 `409 twin_revision_conflict`。配置包含工程点位、业务资产/指标、模型实例/资源/节点名、位移/旋转/姿态/显示绑定、碰撞盒/配对和顺控步骤。可选 `description`（最多 4000 字符）用于公开案例能力与未实现事项。节点存在性和唯一性由加载真实 GLB 的浏览器验证，后端验证项目、资产、实例、资源、绑定层级和数值范围。驱动实例不能同时播放原生动画；改场景/资源或重命名被引用资产 ID 需要先修改绑定。

点位可配置唯一的精确 `topic`（1–200 字符，仅字母、数字、`.`、`_`、`:`、`/`、`-`，首字符必须是字母或数字）。可选 `simulation: {enabled,procedureId,repeat}` 是用户保存的后端自动源配置；启用时必须同时启用数据驱动、配置点位/模型绑定、为所有点位填写 topic 并选择已有顺控。API 服务每 100 ms 在独立的 `twinAutomaticTaskScheduler` 上调度、每秒发现已保存配置，不与 WebSocket 发布或其他定时任务共用执行线程；它无需任何浏览器连接、订阅或命令即可初始化并运行，关闭全部预览页仍继续生产数据。配置未启用 automatic 时保留旧的手动模式，不自动初始化。

同源 Cookie WebSocket `/api/v1/twin-drive?projectId=…` 握手精确校验 Origin，连接期间重复检查会话、项目权限及 Origin；只有 owner/editor/管理员可以手动命令，viewer 可以订阅观察。读取文档、封面、自动源快照以及订阅都不启动或推进自动源。订阅和自动快照只在 PostgreSQL 项目锁内读取配置/已持久化样本，不争抢 Redis 积分租约；多个观察者连接不会因生产者持有租约而收到 twin_runtime_busy。协议版本 1：

```js
// 收到 hello 后订阅此项目当前配置版本的完整精确 topic 集合，不允许通配符、跨项目或部分订阅。
ws.send(JSON.stringify({type:'subscribe', expectedRevision,
  topics:['changsha/lift/position','changsha/conveyor/position']}));
// 服务先确认 subscribed {revision,topics}，然后发送 snapshot，点位样本会带配置的 topic。
// 仅旧的未配置 topic 项目允许直接收快照；config_changed 后必须重新读取配置并订阅。
// 下列命令仅手动模式可用；automatic 启用时全部返回 409 twin_automatic_mode。
ws.send(JSON.stringify({type:'command', commandId:crypto.randomUUID(), expectedRevision,
  operation:'reset'})); // 手动模式明确初始化配置中的 initialValue
ws.send(JSON.stringify({type:'command', commandId:crypto.randomUUID(), expectedRevision,
  operation:'move', values:[{pointId:'lift-position',value:12}]}));
// 其他 operation：set、pause、resume、run-procedure（需 procedureId）、stop-procedure。
// set 是注入实际值；move 是设备模拟器以配置 maxSpeed 向目标积分，浏览器只应用实际值。
// 每 10 秒发送 {type:'ping'}，服务返回 pong；45 秒未收到客户端消息则关闭。
```

服务发送 `hello`、`subscribed {revision,topics}`、约 10 Hz 全量 `snapshot`、`command_ack {commandId,sequence}`、`error {commandId?,error,message}` 和 `config_changed {revision}`。错误 topic 返回 `400 twin_topic_subscription_invalid`，旧版本返回 `409 twin_revision_conflict`。快照带项目、配置版本、序号、时间、`source:simulator`、idle/running/paused/error 状态、点位实际值/目标值/质量/时间/topic 及顺控状态。初始 idle 的 points 为空，不伪造采样。浏览器断线时按 staleAfterMs 冻结本地模型、不外推，后端自动源照常运行；重连按最新实际值显示。

自动执行器真正超过一秒未推进（如 API 停机、持续锁竞争）进入明确 error，保留实际值，不补跑停机时间。错误文本会要求编辑者重新保存配置后重启；本阶段不会静默恢复。手动模式对应情况保持原先 paused，需明确 resume/reset。保存新配置生成新 revision，旧样本作废、客户端收到 config_changed 后重新订阅；新配置若启用自动源，下一轮后端调度从配置 initialValue 明确重新初始化，否则回到 idle。该重新初始化是配置保存的语义，不是每次打开预览重新播放。

顺控只在当前步骤所有实际点位到达目标容差后推进；timeoutMs 只用于报错，绝不是定时切下一动作。自动源 repeat 在末步实际到位后回到首步目标，保留所有实际值，不 reset 或瞬移；用户应在顺控中配置返程目标。失败停在原步骤并公开错误；手动暂停时间不计超时，手动顺控时 set/move 被拒绝。碰撞为浏览器场景中配置盒的重叠事件，并非经过认证的物理引擎或安全 PLC 联锁；topic 是本项目 WebSocket 订阅标识，不代表已接入 MQTT broker、上游 PLC 或真实设备控制。

运行状态由 Valkey 项目同槽 key 保存（空闲 24 小时过期），5 秒带 token 的租约及 Lua 写入栅栏防止多个 API 同时积分。自动源仅调度器积分，任意观察者只读取相同状态；同一时刻多个 API tick 不会重复积分。调度器按数据库真实 tenant/project 配对运行，不冒充用户，逐项目重查配置并加项目行锁。缓存丢失时，已明确启用的自动配置从 initialValue 重新启动；手动配置回到 idle。暂时锁竞争跳过本轮，不伪造新样本，持续故障记录项目/租户日志并显露为陈旧或 error。命令 ID 去重窗口为最近 128 个成功命令、同配置版本且运行缓存尚在；相同 ID 不同内容明确冲突，不承诺无限期/跨 Valkey 数据丢失的恰好一次。配置与命令进入审计日志；Redis 和 PostgreSQL 之间无跨存储事务，失败会明确报告命令结果不确定，客户端不得盲目自动重发。

预算：配置 512 KiB，点位/绑定各 128、碰撞盒 64、碰撞规则 128、顺控 16（每个 64 步）、每个姿态绑定 64 个采样姿态；每项目每秒 20 条成功命令。最多 128 个已启用自动源项目，保存时由 PostgreSQL advisory transaction lock 串行校验总量，调度发现有界且超量明确报错。每 API 最多 128 条驱动连接、输入 32 KiB、单连接每秒 40 条消息、发送缓冲 512 KiB/超时 5 秒。上限不是压测后的吞吐承诺。

回归：`TwinDriveDocumentsTest`、`TwinDriveEngineTest`、`TwinDriveRuntimeTest`、`TwinDriveWebSocketTest` 覆盖契约、引用与原生动画冲突、范围、实际位置驱动、到位顺控/超时/暂停、自动返程循环、无观察者运行、观察读取无副作用、命令隔离、精确 topic/版本订阅、配置版本失效、去重/权限/栅栏/速率、多观察者复用与会话撤销。

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
