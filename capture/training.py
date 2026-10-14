"""Real CUDA 3D Gaussian optimization. Imported only after the availability probe."""
from __future__ import annotations

from functools import lru_cache
import math
import time

from core import MAX_GAUSSIANS, SH_DEGREE, fail

TRAINING_STEPS = 7000
SH_C0 = 0.28209479177387814


def train(images_dir, views, points, colors, execution):
    import numpy as np
    import torch
    import torch.nn.functional as F
    from gsplat import MCMCStrategy, rasterization
    from PIL import Image
    from scipy.spatial import cKDTree

    torch.manual_seed(42)
    torch.set_num_threads(4)
    device = "cuda"
    camera_positions = np.stack([view["c2w"][:3, 3] for view in views])
    scene_scale = float(np.linalg.norm(camera_positions - camera_positions.mean(axis=0), axis=1).max())
    if not math.isfinite(scene_scale) or scene_scale <= 1e-7:
        fail("coverage_insufficient", "相机移动范围不足以初始化空间训练。")
    distances, _ = cKDTree(points).query(points, k=4, workers=4)
    spacing = np.sqrt(np.mean(distances[:, 1:] ** 2, axis=1))
    if not np.isfinite(spacing).all() or np.any(spacing <= 0):
        fail("reconstruction_failed", "稀疏点包含重复位置或无效间距。")
    initial_scales = np.log(spacing)
    if np.any(initial_scales < -12) or np.any(initial_scales > 9):
        fail("reconstruction_failed", "当前空间的相对尺度超出漫游渲染范围。")
    count = len(points)
    quats = torch.zeros((count, 4), dtype=torch.float32, device=device)
    quats[:, 0] = 1
    params = torch.nn.ParameterDict({
        "means": torch.nn.Parameter(torch.from_numpy(points).to(device)),
        "scales": torch.nn.Parameter(torch.from_numpy(np.repeat(initial_scales[:, None], 3, axis=1).astype(np.float32)).to(device)),
        "quats": torch.nn.Parameter(quats),
        "opacities": torch.nn.Parameter(torch.full((count,), math.log(0.1 / 0.9), device=device)),
        "sh0": torch.nn.Parameter(torch.from_numpy(((colors - 0.5) / SH_C0)[:, None, :]).to(device)),
    })
    rates = {"means": 1.6e-4 * scene_scale, "scales": 5e-3, "quats": 1e-3, "opacities": 5e-2, "sh0": 2.5e-3}
    optimizers = {key: torch.optim.Adam([value], lr=rates[key], eps=1e-15) for key, value in params.items()}
    strategy = MCMCStrategy(cap_max=MAX_GAUSSIANS, refine_start_iter=500, refine_stop_iter=6000, refine_every=100, verbose=False)
    strategy.check_sanity(params, optimizers)
    state = strategy.initialize_state()
    camera_tensors = [(torch.from_numpy(view["w2c"]).to(device)[None], torch.from_numpy(view["K"]).to(device)[None]) for view in views]

    @lru_cache(maxsize=8)
    def pixels(index):
        with Image.open(images_dir / views[index]["name"]) as image:
            if image.size != (views[index]["width"], views[index]["height"]):
                fail("reconstruction_failed", "去畸变图像与相机尺寸不一致。")
            return torch.from_numpy(np.asarray(image.convert("RGB"), dtype=np.float32) / 255).contiguous()

    def render(index):
        view = views[index]
        w2c, intrinsic = camera_tensors[index]
        return rasterization(means=params["means"], quats=F.normalize(params["quats"], dim=-1), scales=torch.exp(params["scales"]), opacities=torch.sigmoid(params["opacities"]), colors=params["sh0"], viewmats=w2c, Ks=intrinsic, width=view["width"], height=view["height"], sh_degree=SH_DEGREE, packed=True, near_plane=max(scene_scale * 1e-4, 1e-6), far_plane=scene_scale * 1000, backgrounds=torch.zeros((1, 3), device=device))

    def structural_loss(prediction, target):
        # Local SSIM in pure PyTorch: no JIT-compiled fused extension at runtime.
        a, b = prediction.permute(0, 3, 1, 2), target.permute(0, 3, 1, 2)
        ma, mb = F.avg_pool2d(a, 11, 1, 5), F.avg_pool2d(b, 11, 1, 5)
        va = F.avg_pool2d(a * a, 11, 1, 5) - ma * ma
        vb = F.avg_pool2d(b * b, 11, 1, 5) - mb * mb
        covariance = F.avg_pool2d(a * b, 11, 1, 5) - ma * mb
        similarity = ((2 * ma * mb + 0.01**2) * (2 * covariance + 0.03**2)) / ((ma * ma + mb * mb + 0.01**2) * (va + vb + 0.03**2))
        return 1 - similarity.mean()

    execution.progress("training", 0, TRAINING_STEPS)
    last_update = time.monotonic()
    order = torch.randperm(len(views)).tolist()
    for step in range(TRAINING_STEPS):
        execution.check()
        if step and step % len(views) == 0:
            order = torch.randperm(len(views)).tolist()
        index = order[step % len(views)]
        target = pixels(index).to(device)[None]
        rendered, _, info = render(index)
        loss = 0.8 * F.l1_loss(rendered, target) + 0.2 * structural_loss(rendered, target)
        # MCMC regularization controls opaque floaters and excessive Gaussian extent.
        loss = loss + 0.01 * torch.sigmoid(params["opacities"]).mean() + 0.01 * torch.exp(params["scales"]).mean() / scene_scale
        if not torch.isfinite(loss):
            fail("training_failed", "训练出现非有限损失，当前素材无法稳定生成。")
        loss.backward()
        means_lr = rates["means"] * (0.01 ** (step / TRAINING_STEPS))
        optimizers["means"].param_groups[0]["lr"] = means_lr
        for optimizer in optimizers.values():
            optimizer.step()
            optimizer.zero_grad(set_to_none=True)
        strategy.step_post_backward(params, optimizers, state, step, info, lr=means_lr)
        if len(params["means"]) > MAX_GAUSSIANS:
            fail("resource_limit", "训练高斯点数超过 100 万。")
        if time.monotonic() - last_update >= 5 or step + 1 == TRAINING_STEPS:
            execution.progress("training", step + 1, TRAINING_STEPS)
            execution.log.write((f"training step={step + 1} loss={float(loss.detach()):.7g} gaussians={len(params['means'])}\n").encode())
            execution.log.flush()
            last_update = time.monotonic()

    # This is a measured training-view fit, not a claim about unobserved views.
    scores = []
    with torch.no_grad():
        for index in range(len(views)):
            rendered, _, _ = render(index)
            mse = F.mse_loss(rendered, pixels(index).to(device)[None])
            score = -10 * torch.log10(torch.maximum(mse, torch.tensor(1e-12, device=device)))
            scores.append(float(score))
            execution.check()
            if time.monotonic() - last_update >= 5:
                # The optimization is complete; keep the phase alive while measuring fit.
                execution.progress("training", TRAINING_STEPS, TRAINING_STEPS)
                execution.log.write((f"training evaluation views={index + 1}/{len(views)}\n").encode())
                execution.log.flush()
                last_update = time.monotonic()
        if not all(math.isfinite(score) for score in scores) or float(np.mean(scores)) < 18:
            fail("training_failed", "训练视角拟合质量不足，请减少动态物体、模糊和曝光变化后重新采集。")
        quaternions = F.normalize(params["quats"], dim=-1)
        values = {key: value.detach().cpu().numpy() for key, value in params.items()}
        values["quats"] = quaternions.cpu().numpy()
    metrics = {"trainingSteps": TRAINING_STEPS, "trainingMeanPsnr": float(np.mean(scores)), "appearance": "sh0"}
    return values, metrics, scene_scale


def ply_rows(values):
    for index in range(len(values["means"])):
        yield [*values["means"][index], 0, 0, 0, *values["sh0"][index, 0], values["opacities"][index], *values["scales"][index], *values["quats"][index]]


def camera_manifest(views, scene_scale):
    result = []
    for view in views:
        matrix = view["c2w"]
        position = matrix[:3, 3]
        result.append({"image": view["name"], "position": position.tolist(), "target": (position + matrix[:3, 2] * scene_scale).tolist(), "up": (-matrix[:3, 1]).tolist(), "c2w": matrix.tolist()})
    return result
