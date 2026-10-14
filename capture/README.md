# 现场照片 / 视频 → 3D Gaussian 写实漫游

**当前开放状态（2026-10-14）：因服务器暂不具备 GPU 重建条件，平台前端已暂时隐藏此功能入口；API 与本目录实现保留，待目标 GPU 部署及真实素材验收完成后再恢复。**

本目录是真实重建工作程序及离线镜像构建脚本。现场图片先通过 COLMAP 估计相机和稀疏空间点，再使用 gsplat CUDA 光栅化、Adam 优化和 MCMC 增密训练 7,000 步，最后导出标准 Gaussian PLY。训练使用 SH0 外观；不是把照片贴在平面上，也不生成虚构的成功结果。

**当前验收边界：开发环境没有 NVIDIA GPU。已执行 CPU 输入/输出、图片解码、真实 FFmpeg 抽帧和失败协议测试；尚未构建 CUDA 镜像、尚未导出离线镜像包、尚未执行真实 GPU 重建及浏览器效果验收。源码和打包工具就绪，不代表可直接交付的离线镜像已验证。**

## 素材要求与可实现范围

- 同一静态区域的 8–120 张 JPG / JPEG / PNG / WebP 照片，单张不超过 8 MiB，总计不超过 500 MiB；或一个 MP4 / WebM 视频，不超过 100 MiB。
- 照片短边至少 360 像素、长边至少 480 像素，单张不超过 4,000 万像素。动画、透明图片、解码错误、完全重复画面及明显模糊/低纹理画面会被拒绝。
- 视频 4 秒–10 分钟，1–60 fps，单个画面轨道；短边至少 360 像素，总像素不超过 3840×2160，最长边不超过 4096。按时长均匀抽取 8–120 帧，抽帧频率最多 2 fps。
- 采集时移动相机位置，连续视角保持明显重叠，围绕需要重建的区域拍摄不同高度/方向；原地旋转不能提供可靠深度。保持光照、焦距和曝光稳定，避免运动物体、强反光、玻璃和大片纯色表面。
- 不要求照片来自同一相机，但不同焦距、强畸变和混杂照片会降低匹配成功率。输入满足大小/数量等形式要求并不能保证场景可重建；空间连通性和训练质量以真实分析结果为准。
- 最长边统一限制为 1500 像素。注册相机至少 8 个且占输入视角的 80%，稀疏空间点至少 500 个，平均重投影误差不超过 2.5 像素，并检查相机位移。训练视角平均 PSNR 至少 18 dB；该指标仅反映已拍视角拟合，不代表未拍区域或新视角质量保证。
- 最大 1,000,000 个高斯，PLY 最大 200 MiB；当前 SH0 每点 68 字节，因此点数上限会先于文件大小上限生效。单任务工作目录上限 8 GiB、5,000 文件、最长 3 小时，各外部步骤另设超时。
- 保持 COLMAP 相对坐标；**没有真实单位标定，不能直接测量尺寸**。输出属于用于漫游的 Gaussian PLY，不是可编辑的 BIM、实体网格或 GLB。SH0 无法完整表达随视角变化的反光；遮挡、未拍区域和弱纹理区域可能不完整。

## 运行协议

仅允许后端创建的本地输入文件；不接收 URL、用户命令、压缩包或任意训练参数。所有源文件必须位于清单所在目录内，不能使用符号链接。输出目录必须为空。

```sh
python worker.py --probe
python worker.py --input /work/job/input/manifest.json --output /work/job/output
```

清单格式（照片必须实际列出 8–120 项）：

```json
{"version":1,"inputKind":"video","sources":[{"path":"scene.mp4","filename":"scene.mp4"}],"options":{}}
```

照片使用 `inputKind: "images"`。`path` 可为清单目录内的相对路径或绝对路径。显示文件名限制为 240 字符，不能包含路径分隔符、控制字符或重复名称。

`--probe` 输出单行 JSON，字段为 `available`、`cuda`、`colmap`、`ffmpeg`、`gsplatVersion`、`precompiledExtension`、`reason`。缺少依赖/GPU时报告 `available:false`，不伪造可用状态。可用探测必须在实际 GPU 执行最小 gsplat 前向和反向 CUDA 核。检查固定 COLMAP/gsplat 版本和已编译的 `gsplat.csrc`；禁止运行时补装、下载或 JIT 编译扩展。

任务 stdout 仅含 JSONL 进度：

```json
{"stage":"matching","completed":0,"total":0}
{"stage":"training","completed":100,"total":7000}
```

阶段为 `validating / extracting / matching / reconstructing / training / exporting`。`0/0` 表示阶段进度未知，不显示虚构百分比。外部命令每 10 秒报告阶段心跳，训练每约 5 秒报告完成步数。stderr 和 `worker.log` 保留诊断上下文，失败写 `error.json` 并非零退出；失败时还会把最多 8 KiB / 40 行、每行最多 1,000 字符的原生命令日志尾部发送到 stderr，由 Java 带任务 ID 保存到服务器日志，防止工作目录清理后丢失根因。只有成功退出且 `result.json` 和 PLY 都通过后端校验才能发布。

`result.json` 包含 `format:"ply"`、固定文件名 `file:"scene.ply"`、精确 `byteSize`、`gaussianCount`、`bounds:{min,max}`（高斯中心范围）、`initialCamera:{position,target,up}`、`quality:{inputImages,registeredImages,points3D,meanReprojectionError}`、`warnings`、`registeredCameras`、`coordinateSystem:"colmap"`、`units:"arbitrary"`、`training`。

相机包含 COLMAP 世界下的 `c2w` 4×4 矩阵。COLMAP 相机朝 +Z、上方为 -Y；初始目标由相机正向计算，上方向为单位向量且与视线正交。PLY 为 `binary_little_endian`、每字段 float32，严格顺序为 `x y z nx ny nz f_dc_0 f_dc_1 f_dc_2 opacity scale_0 scale_1 scale_2 rot_0 rot_1 rot_2 rot_3`。颜色为 SH0 系数，opacity 为 logit，scale 为对数（范围 -12 到 9），旋转为单位 wxyz 四元数。不输出 `f_rest_*`，不会静默丢弃训练过的高阶球谐。

受控错误代码：`invalid_manifest`、`input_invalid`、`image_decode_failed`、`image_quality_insufficient`、`video_invalid`、`coverage_insufficient`、`reconstruction_failed`、`training_failed`、`resource_limit`、`worker_unavailable`、`timeout`、`output_invalid`、`internal_error`。

## 离线镜像构建与部署

构建在**可联网 Linux amd64** 机器上完成，编译阶段不需要 GPU；实际运行需要兼容 NVIDIA 驱动、NVIDIA Container Toolkit 和目标 GPU。构建机需预留足够磁盘/内存用于 CUDA、PyTorch、Java 和本机库编译。ARM Mac 跨架构编译成本较高，本次没有在开发机上尝试该构建。

镜像包含 Java 后端 JAR、Python 虚拟环境、CPU COLMAP、FFmpeg、CUDA/PyTorch 和**预编译** gsplat 扩展，运行时不需要外网或 pip。固定主要版本：CUDA 12.4.1、PyTorch 2.5.1、gsplat 1.5.3、COLMAP 3.11.1；Python 直接依赖见 requirements.txt。系统包/传递依赖在联网构建时解析，实际版本、上游提交和架构记录保存在镜像 `/opt/capture-build`，并非声称所有重建均逐位可复现。

默认编译 GPU 架构 `7.5;8.0;8.6;8.9;9.0`，不提供依赖 PTX 现场编译的隐式回退。其他 GPU 架构必须在可联网构建端选用支持它的工具链并重新构建/验收，不能仅因驱动识别出 GPU 就启用任务。当前固定 CUDA 12.4 栈不承诺支持更新架构。

从后端仓库根目录执行：

```sh
# 在联网 Linux amd64 构建端：真实构建后才会输出可搬运的 tar 与 SHA-256。
CAPTURE_CUDA_ARCH_LIST='7.5;8.0;8.6;8.9;9.0' \
  bash capture/package-offline.sh factory-twin-capture:1 /exports/capture-bundle
```

拷贝整个输出目录到已装好驱动和 Container Toolkit 的离线 GPU 服务器（宿主需 Docker、sha256sum、Python 3）：

```sh
cd /exports/capture-bundle
bash load-offline.sh
```

导入脚本先校验 SHA-256，再加载镜像，使用 `--network none` 执行 GPU 核自检。自检失败则脚本失败，不能启用任务。完成自检后通过项目的可选 capture worker compose 配置接入既有数据库/共享文件卷；该镜像 `/app/backend.jar` 与普通 Java 服务共用代码，具体 role/数据库配置由部署 compose 管理。运行用户为 `twin`，`/work` 归其所有；支持只读根目录，临时文件/缓存写 `/tmp` 或配置的 `/work`。无需重建既有业务数据库。

随后用一组真实、已获授权的现场照片/视频验收，输入目录包含清单及源文件，输出目录应为尚不存在的新目录：

```sh
bash accept-gpu.sh factory-twin-capture:1 /data/real-capture /data/capture-result-001
```

该步骤断网运行、限制为 4 CPU / 16 GiB RAM / 256 进程并限制临时目录，默认内存可以通过 `CAPTURE_MEMORY_LIMIT` 调整。此限额不是性能或显存保证；显存不足会真实失败，需缩小素材或配置合适 GPU。验收至少检查：重建成功、中心范围/相机方向正确、已拍主体与输入一致、主要结构有覆盖、移动视角没有严重漂浮物/空洞、任务可取消、页面保持响应。不能用人工编造的 PLY 或单元测试中的噪声/测试视频替代真实重建效果验收。

## 本地 CPU 验证

不需要安装 PyTorch、CUDA、COLMAP：

```sh
python3 -m unittest discover -s capture -p 'test_*.py' -v
python3 -m py_compile capture/*.py
bash -n capture/package-offline.sh capture/load-offline.sh capture/accept-gpu.sh
```

测试需要 Pillow 和 NumPy；真实视频抽帧测试在没有 FFmpeg 时明确跳过。测试覆盖路径/数量/大小约束、异常素材、透明及重复图片、分辨率约束、抽帧/视频边界、相机坐标、PLY 字节布局/尺度/旋转/范围、进度协议、失败日志和不可用状态。另有真实子进程组的 SIGTERM 取消及超时回收、诊断日志和工作目录预算测试。离线加载脚本使用隔离的 Docker 命令替身验证校验和错误、GPU 不可用时不会误报通过；该脚本测试不加载真实镜像，也不能验证 CUDA 训练本身。

## 上游资料与许可

- [COLMAP 3.11.1](https://github.com/colmap/colmap/tree/3.11.1)、[CLI](https://colmap.github.io/cli.html)、[模型与坐标格式](https://colmap.github.io/format.html)，[BSD-3-Clause 许可](https://github.com/colmap/colmap/blob/3.11.1/COPYING.txt)。本镜像关闭不需要的 CGAL/LSD。
- [gsplat 1.5.3](https://github.com/nerfstudio-project/gsplat/tree/v1.5.3)、[MCMCStrategy](https://github.com/nerfstudio-project/gsplat/blob/v1.5.3/gsplat/strategy/mcmc.py)、[官方训练顺序参考](https://github.com/nerfstudio-project/gsplat/blob/v1.5.3/examples/simple_trainer.py)，[Apache-2.0 许可](https://github.com/nerfstudio-project/gsplat/blob/v1.5.3/LICENSE)。工作程序自行编排训练，没有加入受限的原始 3DGS 仓库实现。
- [PyTorch 2.5.1 官方安装版本](https://pytorch.org/get-started/previous-versions/)、[NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html)。镜像中的 CUDA、FFmpeg 及其他依赖保留各自许可，不能把整个镜像笼统称为 Apache 许可。
