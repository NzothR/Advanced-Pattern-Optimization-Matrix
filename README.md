# 高级样板优化矩阵 / Advanced Pattern Optimization Matrix

为 GTNH 2.8.4 开发的 AE2 附属模组。在网络中放置此方块后，合成计算时会自动将处理样板的输入输出量按需翻倍，大幅减少 CPU 发配次数。

A Minecraft 1.7.10 AE2 addon for GTNH 2.8.4. Place this block in your ME network and processing pattern I/O is automatically scaled during crafting calculations, dramatically reducing CPU dispatches for large jobs.

---

## 功能特性 / Features

    即插即用 — 接入 ME 网络即生效，无 GUI、无需配置
    Plug-and-play — connects to your ME network and works immediately; no GUI, no configuration.

    运行时翻倍 — 不修改原始样板数据，仅在合成计算时透明缩放输入输出量
    Runtime scaling — does not modify stored pattern data; scales I/O transparently during crafting calculation.

    全自动倍率 — 根据下单数量自动计算最优倍率，无需手动设置
    Automatic multiplier — computes the optimal multiplier based on the requested quantity with no manual input.

    安全上限 — 翻倍后任何单个物品数量不超过 2,147,483,647 (Integer.MAX_VALUE)
    Safe ceiling — no single item stack ever exceeds 2,147,483,647 (Integer.MAX_VALUE) after scaling.

    极简方块 — 无 GUI、无配置界面、无红石控制，纯存在检测
    Minimal block — no GUI, no config UI, no redstone control; pure presence detection.

## 效果示例 / Example

    样板: 1 铁锭 → 1 铁板，下单 123 个铁板
    Pattern: 1 Iron Ingot → 1 Iron Plate, request 123 plates

    原版 AE2: CUP 发配 123 次，每次 1 个铁锭
    Vanilla AE2: 123 dispatches, 1 ingot each

    使用 APOM: 自动缩放为 123 铁锭 → 123 铁板，CUP 发配 1 次
    With APOM: auto-scaled to 123 Ingots → 123 Plates, 1 dispatch

## 灵感来源 / Inspiration

    本模组的翻倍机制参考 ExtendedAE_Plus (EAEP) 的 ScaledProcessingPattern 设计，
    将其从现代 AE2 (1.20+) 移植到 1.7.10 的 ICraftingPatternDetails 体系。
    EAEP: https://github.com/GaLicn/ExtendedAE_Plus

    The doubling mechanism is inspired by ExtendedAE_Plus (EAEP)'s ScaledProcessingPattern,
    backported from modern AE2 (1.20+) to the 1.7.10 ICraftingPatternDetails API.
    EAEP: https://github.com/GaLicn/ExtendedAE_Plus

## 使用方法 / Usage

    1. 合成「高级样板优化矩阵」方块
    2. 将其放置在 ME 网络中（通过 AE2 线缆连接）
    3. 在 ME 终端中下单任意处理样板产物
    4. 合成计算自动生效，无需额外操作
    5. 移除方块后网络恢复原版行为

    1. Craft the "Advanced Pattern Optimization Matrix" block.
    2. Place it in your ME network (connected via AE2 cable).
    3. Request any processing pattern output through an ME terminal.
    4. Scaling takes effect automatically — nothing else to do.
    5. Removing the block restores vanilla AE2 behavior.

## 简要原理 / How It Works

    AE2 在计算合成任务时会查询网络中的样板列表，本模组通过 Mixin 注入到该查询点，
    在返回样板列表前将处理样板包装为 ScaledPatternDetails。
    这个包装类将样板的输入输出乘以计算出的倍率，而对 AE2 其他系统完全透明。

    倍率计算：根据下单数量与样板单次产出计算理想倍率，同时遍历样板所有物品栈
    确保 baseAmount × multiplier ≤ 2,147,483,647，取两者的较小值。

    网络中是否存在矩阵通过维度 ID + 引用计数追踪，支持同维度多个矩阵共存。

    AE2 queries the network for pattern lists when calculating crafting jobs.
    This mod injects into that query point via Mixin and wraps processing patterns
    with ScaledPatternDetails before they are returned. The wrapper multiplies
    all I/O by a computed multiplier while remaining transparent to the rest of AE2.

    Multiplier logic: ideal = ceil(requestAmount / patternOutput), then clamp to
    ensure baseAmount × multiplier ≤ Integer.MAX_VALUE for every stack in the pattern.

    Network presence is tracked by dimension ID with reference counting, supporting
    multiple matrices in the same dimension.

## 构建 / Building

    ./gradlew build
    产物位于 build/libs/

    ./gradlew build
    Output jar is in build/libs/.

## 依赖 / Dependencies

    AE2: Applied-Energistics-2-Unofficial rv3-beta-695-GTNH (必需 / required)
    GTNHLib: 0.6.12+ (提供 Mixin 运行时 via UniMixins / provides Mixin runtime)

## 许可证 / License

    MIT — 基于 GTNH 模组模板构建
    MIT — built with the GTNH mod template
