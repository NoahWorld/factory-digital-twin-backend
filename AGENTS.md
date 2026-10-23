# Kingdom 3D vision Java 后端协作规则

## 分支约定

- 用户于 2026-10-20 再次明确：所有修改统一在 `main` 分支进行，不自行创建新分支（包括 `codex/*`）。这是本项目的明确分支约定；只有用户后续明确要求时才能更改。分支整理必须保留已有提交和未提交改动，确认合并及远端同步完成后再删除旧分支。

## 仓库范围

- Owner 要求在本仓库 `main` 与平台前端 `main` 配套开发。此前既有能力同步及四项场景功能已完成本地验证，当时授权范围为提交并推送两个仓库的 `main`、不包含外部部署；这是历史范围，不限制下述另行授权的服务器发布。原平台 `newpower` 目标保持暂停。
- 2026-10-13 Owner 授权的 BIM/CAD 工程底座完整前后端已于 `06:57:49.276338Z` 部署到 `http://8.136.35.33:19080` 并通过生产验收。运行后端来自本仓库应用提交 `43ec0a91e2becea96274ee0290a062ff4382e936`，镜像为 `dtwin/backend:43ec0a9-amd64`；配套平台应用提交 `968009b3a0c452fcad56612c7bcbc27a4b43347c`，release `dtwin-engineering-20261013-968009b`。服务器原生禁网 12 项、真实工程几何 5 项、公网接口 10 项、普通 HTTP 浏览器 8 项与工程界面 8 项验收通过，备份恢复及既有业务/其他容器核对一致；V1–V8 迁移成功。后续验收脚本和文档提交不改变已发布应用产物，证据以平台 `deploy/server/DEPLOYMENT.md` 本轮记录为准。
- `decorations`、`roomAlarms`、`staticMap` 通过原 scene 文档事务保存；settings-only 保留它们及 fluids，省略不清空。报警使用业务 assetId/metricKey 和实例/资源引用，每个 metric 的时间与 sourceId 必须对应，不能取其它源的聚合时间。几何预算及结构由平台共享定义生成，Java补语义与最终引用检查。
- 固定版本捕获应识别内置模型和内置图片的不可变 ID，不查询虚构的上传资源；单数公开分享与复数固定版本保持各自既有语义。

- 本仓库包含 Java 21 + Spring Boot 后端及 `engineering/` 离线 Python 几何转换子进程；前端和平台级编排位于 `NoahWorld/factory-digital-twin-platform`。
- `src/main/resources/contracts/` 是与前端共享契约的已生成快照。修改契约时必须在平台仓库重新生成，并同步更新这里的文件与测试。
- 行业模板图片使用平台 `shared/builtin-images.ts` 生成的 `builtin-images.json` 精确白名单，包含 ID、版本、路径、字节数与 SHA-256。项目只存资源 ID；未知 ID、资源类型不匹配必须报错，内置资源不可删除。静态图片由平台前端分发，不能将任意 URL 当作内置资源；公开发布只允许读取当前文档引用的资源。
- 场景和 `model-3d` 的 `preventBottomView` 是默认开启的正式布尔配置。Java 从生成契约补齐旧文档中缺失的字段，保留显式 `false`，拒绝 `null` 和非布尔值；新项目、场景读取、保存与资源 manifest 必须返回一致设置，不维护浏览器专用副本。
- PostgreSQL 迁移只放在 `src/main/resources/db/migration/`，由 Flyway 管理。
- 2D/3D 点击动作使用平台仓库的 `shared/twin-actions.ts` 生成契约，Java 在保存文档的同一事务中校验最终节点/实例状态、目标引用及项目权限，失败必须回滚。不得保存任意脚本或绕过关联项目授权；对应回归为 `TwinActionsTest` 和平台仓库的 `pnpm backend:smoke:twin-actions`。
- 可选 2D 外部 IoT 点位以平台 `shared/iot-embed.ts` 为契约源。纯文本、指标卡、环形进度节点可保存 `iotBinding={pointKey,valueType,staleAfterSeconds}`，环形进度限 number；名称为 1–200 个 UTF-16 单元、无控制字符和首尾空白，类型为 number/string/boolean，过期时间为 1–86400 秒整数。生成 schema 拒绝 null、未知字段及不支持的组件，Java 在原文档事务内检查最终画布（含未修改节点）的 500 个不同点位预算及同名类型一致性。省略绑定的旧节点不补默认字段；沿用 JSONB、读取/分块、公开分享及固定版本快照保留绑定，不新增迁移或修改原数据源、3D、授权链。运行值仅通过浏览器 `kingdom.iot.points` v1 协议输入，不写回文档；平台旧 Worker 明确拒绝该字段的写入，不得静默丢失。修改后同步生成 `configuration.schema.json`、`iot-binding-limits.json`、`sources.json`，运行 `IotBindingContractTest`、`IotBindingPersistenceTest` 及平台契约检查。
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
- 管路和状态联动采用平台 `shared/process-control.ts` 生成契约：2D `process-pipe` 端点引用在文档保存事务中按最终节点全集校验，不能引用管路或删除仍被引用的组件；管路禁止外部资源、数据绑定和点击动作。节点/3D 流体的可选 `stateBinding` 保存在原 JSONB 文档，通用 pointKey 为 1–200 UTF-16 单元、无首尾空白/控制字符；同文档点位类型一致、最多 500 个不同点位，不限制设备数。数值比较和标量类型严格匹配，运行点值不落库；不新增 IoT 运行端点。生成范围在 `process-control-limits.json`，回归为 `ProcessControlContractTest`、`ProcessControlPersistenceTest`。

## 写实漫游（2026-10-13）

- 2026-10-14 Owner 因服务器暂不具备 GPU 重建条件，要求暂时隐藏平台前端的写实漫游入口与介绍；保留本仓库 API、任务数据和工作程序。恢复公开入口须先完成目标 GPU 部署与真实素材重建验收，不能将保留的接口视为当前已开放能力。
- `SiteCaptures` + Flyway V9 为独立持久任务，支持客户端 UUID 幂等创建、声明素材槽位、原始字节上传、显式开始、状态查询、失败重试、取消和删除。每次读写继续校验租户/项目/模块权限。素材不复用通用媒体表，项目删除必须检查重建与上传状态并清理所属对象。
- `TWIN_MODE=capture-worker` 单独运行 COLMAP + gsplat CUDA 子进程；普通 API、collector、engineering worker 不执行重建。`TWIN_CAPTURE_PYTHON`、`TWIN_CAPTURE_WORKER` 为固定绝对路径，超时默认 7200 秒。可用性只能来自新鲜工作节点心跳及实际 GPU/预编译扩展探测，不能用配置或进程存在冒充 ready。
- Python 输入/输出契约和离线镜像位于 `capture/`。限制 8–120 张照片（单张8 MiB、总500 MiB）或一个视频（100 MiB），输出最多200 MiB/100万高斯点。固定参数、有限工作目录、JSONL 实际阶段/计数，禁止接受任意命令、联网下载、CPU 假重建或吞掉素材错误。Gaussian PLY 由 Java 验证后才发布；失败保留受控错误码、任务 ID 和服务端详细日志。
- 任务租约过期最多自动恢复三次，旧 lease 不得更新或发布；取消、重试、对象清理均使用任务/attempt 追溯。源文件与结果均为私有存储；对外只通过受权任务接口访问。
- 当前无 NVIDIA GPU 服务器；CPU 单元测试、真实 PostgreSQL/HTTP/S3 测试、合成 PLY 渲染测试均不能代替真实素材重建及离线 GPU 镜像验收。平台 `docs/site-capture.md` 与本仓库 `capture/README.md` 为跨端契约和部署说明。

## 安全要求

- 工程底座支持 IFC/DXF → GLB，Flyway V8 持久化源文件引用、检查结果、显式参数、进度、错误和输出资源。Java 处理权限/S3/租约/入库，`engineering/convert.py` 只读取指定本地输入；禁止下载外部模型、接受用户命令行或运行时安装依赖。固定依赖见 `engineering/requirements.txt`，Docker 镜像内置 Python 3.11 与 native wheels，可 `docker save/load` 离线交付。原生开发必须显式设置 `TWIN_ENGINEERING_PYTHON` 和 `TWIN_ENGINEERING_WORKER`。
- 源 IFC/DXF 上限 100 MiB，GLB 沿用 25 MiB；每租户最多 10 个活动任务，每 worker 同时一个转换，默认超时 900 秒。独立执行线程不得阻塞普通任务轮询；租约/心跳失效不得发布结果。失败保留任务 ID、错误码及受控图元定位信息；原始异常只进日志。项目删除必须阻止活动转换并清理源对象。不得把未启动 worker 当成已完成任务。
- 二维 CAD 的单位、每个有内容图层的用途、高度/厚度和标高均显式确认，忽略图层也须明确。闭合轮廓、孔洞、曲线、图块变换必须保持几何含义；不支持的建模实体/无效轮廓明确失败，不能自动补墙、自动修拓扑或悄悄丢弃。输出米制 Y-up；保留 IFC GUID / DXF 句柄及居中偏移。RVT/DWG 需先导出 IFC/DXF，不宣称原生格式支持。平台 `docs/engineering-imports.md` 是跨前后端功能/输入/部署约定。

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
- 工程转换修改执行 `python -m unittest discover -s engineering -p 'test_*.py'`，含真实 IFC 开洞和 DXF 几何/单位/变换/错误用例；镜像中使用 `--network none` 复验。平台 `pnpm backend:smoke:engineering` 只创建自己的临时容器和内网，不连接已有数据，验证真实文件检查、重启恢复、GLB 入库和损坏文件失败。
- 生产 amd64 镜像以 Python 3.11 / Debian Bookworm 运行层交付固定 native wheels；目标服务器的旧内核必须原生执行默认 seccomp、`--network none` 的依赖与几何测试，不能以本机 ARM 或仿真结果替代。服务器变更只使用 `/data/dtwin/bin/docker-dtwin` 操作本项目专用 daemon，禁止修改其他业务 Docker/Nginx。工程 worker 配置为 2 GiB 内存上限、512 MiB `/tmp` tmpfs、JVM `-Xmx384m`，storage 保持 2 GiB；限额不代表内存预占或任意复杂文件均可转换。完整发布先恢复验证数据库和对象备份，再运行平台 `deploy/server/verify-engineering.mjs`、公网接口和真实 HTTP 浏览器验收，按精确 ID 清理自己的测试项目并撤销会话，核对既有业务数据和其他容器状态。
