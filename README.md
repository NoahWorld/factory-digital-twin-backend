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

- 身份：首管理员初始化、账号/邮箱登录、持久会话、退出、用户创建/列表、撤销用户会话；全局角色与项目 owner/editor/viewer 分开校验。会话从数据库取得租户，业务请求不能通过自行填写 tenantId 越权。登录可显式指定 `tenant`；默认 `local`。
- 项目、2D 画布、独立 3D 场景、资产、数据源配置与指标绑定；沿用 `/api/v1`、Cookie、`error/message/requestId` 和 `expectedRevision`。并发保存返回 409；原有错误码 `unauthenticated` 保持一致；文档与资源时间字段输出带 `Z` 的 ISO 8601 UTC 时间，浏览器按本地时区展示。
- 2D 与 3D 项目创建时由数据库触发器建立默认封面；保存文档或修改名称时，在同一事务中重建 `project_covers` 中的 SVG 并递增封面版本。`GET /projects/{id}/cover.svg` 继续经过项目访问控制，并使用 ETag 重新验证缓存；历史占位封面会在首次读取时按当前渲染器重建并记录日志。
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
