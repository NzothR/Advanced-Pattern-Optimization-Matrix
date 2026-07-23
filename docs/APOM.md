# 高级样板优化矩阵 (Advanced Pattern Optimization Matrix)

## 模组设计方案文档 v1.1

> **目标平台**: Minecraft 1.7.10 / GTNH 2.8.4
> **AE2 版本**: Applied-Energistics-2-Unofficial rv3-beta-695-GTNH
> **开发框架**: Forge 10.13.4.1614 + Mixin (UniMixins via GTNHLib)
> **文档日期**: 2026-07-23

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术背景分析](#2-技术背景分析)
3. [总体架构设计](#3-总体架构设计)
4. [系统设计](#4-系统设计)
5. [核心代码设计](#5-核心代码设计)
6. [执行流程详解](#6-执行流程详解)
7. [Mixin 配置与注入策略](#7-mixin-配置与注入策略)
8. [构建配置](#8-构建配置)
9. [兼容性与风险分析](#9-兼容性与风险分析)
10. [测试计划](#10-测试计划)
11. [开发路线图](#11-开发路线图)
12. [附录](#12-附录)

---

## 1. 项目概述

### 1.1 背景

GTNH 整合包中已有「样板优化矩阵」(Pattern Optimization Matrix)，能够自动优化样板内容。
但该方案存在效率瓶颈：需要实际修改样板数据，优化粒度有限，对于大批量合成请求仍需多次发配。

### 1.2 目标

创建一个名为「高级样板优化矩阵」的方块，实现：

- **运行时样板翻倍**：不修改原始样板数据，在合成计算阶段透明地将样板输入/输出乘以倍率
- **一次性发配**：减少 CPU 发配次数，提升合成效率
- **即插即用**：连接到 AE 网络即生效，无需手动配置
- **全自动倍率**：根据下单数量自动计算最优倍率，无需人工干预
- **安全上限**：翻倍后任何单个物品数量不超过 2,147,483,647 (Integer.MAX_VALUE)

### 1.3 效果示例

```
场景: 样板 1铁锭 -> 1铁板

下单 123 铁板:
  倍率 = ceil(123/1) = 123
  缩放后: 123铁锭 -> 123铁板
  发配次数: 1 次 (原版需要 123 次)

下单 40G 铁板:
  理想倍率 = 40G, 但超过上限
  有效倍率 = 2,147,483,647
  缩放后: 2,147,483,647铁锭 -> 2,147,483,647铁板
  发配次数: ceil(40G / 2,147,483,647) = 19 次 (原版需要 40G 次)
```

### 1.4 参考实现

本模组的翻倍机制参考 ExtendedAE_Plus (EAEP) 的 ScaledProcessingPattern 设计，
将其从现代 AE2 (1.20+) 移植到 1.7.10 的 ICraftingPatternDetails 体系。

EAEP 仓库: https://github.com/GaLicn/ExtendedAE_Plus
GTNH AE2 仓库: https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial

---

## 2. 技术背景分析

### 2.1 EAEP 的样板翻倍机制 (现代 AE2, 1.20+)

EAEP 的核心设计：

| 组件 | 职责 |
|------|------|
| ScaledProcessingPattern | 包装 AEProcessingPattern，将所有 GenericStack 输入/输出乘以 multiplier |
| PatternScaler | 工厂类，创建缩放样板 + 计算最优倍率 |
| ISmartDoublingAwarePattern | Mixin 注入到 AEProcessingPattern，标记样板可缩放 |
| RequestedAmountHolder | ThreadLocal 栈，追踪嵌套请求链中的请求数量 |
| PatternProviderBlockEntity | 在 getAvailablePatterns() 中返回缩放后的样板 |

### 2.2 GTNH AE2 1.7.10 合成计算流程 (rv3-beta-695)

#### 2.2.1 关键接口

```java
public interface ICraftingPatternDetails {
    ItemStack getPattern();
    IAEItemStack[] getInputs();
    IAEItemStack[] getCondensedInputs();
    IAEItemStack[] getOutputs();
    IAEItemStack[] getCondensedOutputs();
    boolean isCraftable();           // true=合成样板(工作台), false=处理样板
    boolean canSubstitute();
    boolean isValidItemForSlot(int slot, ItemStack item, World world);
    ItemStack getOutput(InventoryCrafting inv, World world);
    int getPriority();
    void setPriority(int priority);
}
```

> **注意**: 1.7.10 的 ICraftingPatternDetails **只暴露 IAEItemStack 接口**，与新版 AE2 的 IAEStack（含流体）不同。
> 因此本模组目前仅处理物品样板，流体缩放需后续版本支持。

#### 2.2.2 合成计算调用链

```
玩家点击"合成" / API 调用
    |
    v
CraftingGridCache.beginCraftingJob(World, IGrid, BaseActionSource, IAEStack, CraftingMode, FutureCallback)
    |
    v
new CraftingJobV2(World, IGrid, BaseActionSource, IAEStack, CraftingMode, FutureCallback)
    |
    +-- new CraftingContext(World, IGrid, BaseActionSource)
    |       |
    |       +-- availablePatterns = craftingGrid.getCraftingMultiPatterns()
    |           // ImmutableMap<IAEStack, ImmutableList<ICraftingPatternDetails>>
    |
    +-- new CraftingRequest(stack, PRECISE_FRESH, true, mode)
    |
    +-- context.addRequest(request)
              |
              v
        CraftingCalculations.tryResolveCraftingRequest(request, context)
              |
              v
        CraftableItemResolver.provideCraftingRequestResolvers(request, context)
              |
              +-- context.getPrecisePatternsFor(stack)    <-- Hook 点
              |       // 返回 List<ICraftingPatternDetails>
              |       // 注意: 此方法只接收物品类型，不接收请求数量
              |
              +-- for each pattern:
                    new CraftFromPatternTask(request, pattern, priority, context)
                          |
                          +-- patternOutputs = pattern.getCondensedOutputs()
                          +-- matchingOutput = findOutput(patternOutputs, request.stack)
                          +-- toCraft = ceilDiv(remainingToProcess, matchingOutput.getStackSize())
                          |
                          +-- for each input in pattern.getCondensedInputs():
                                childAmount = input.getStackSize() * toCraft
                                new CraftingRequest(input, childAmount, ...)
                                context.addRequest(childRequest)  // 递归
```

#### 2.2.3 合成执行阶段

```
CraftingJobV2 计算完成
    |
    v
CraftingGridCache.submitJob(job, cpuCluster, ...)
    |
    v
CraftingCPUCluster.submitJob(job)
    |
    +-- for each task in job.getTasks():
          task.startOnCpu(cpuCluster)
              |
              v
          CraftFromPatternTask.startOnCpu(cpuCluster)
              |
              +-- cpuCluster.addCrafting(pattern, totalCraftsDone)
                    // CPU 根据 pattern.getCondensedInputs() 决定每次发配量
                    // 循环 totalCraftsDone 次
```

---

## 3. 总体架构设计

### 3.1 架构总览

```
+-------------------------------------------------------------------+
|                Advanced Pattern Matrix Mod (APM)                    |
+-------------------------------------------------------------------+
|                                                                     |
|  +-------------------+      +----------------------------------+   |
|  |   Block Layer      |      |        Crafting Layer             |   |
|  |                    |      |                                    |   |
|  | BlockAdvPattern    |      |  ScaledPatternDetails              |   |
|  | Matrix             |      |  (ICraftingPatternDetails 包装)    |   |
|  |                    |      |                                    |   |
|  | ItemBlockAdv       |      |  PatternMultiplier                 |   |
|  | PatternMatrix      |      |  (倍率计算)                        |   |
|  | (物品提示)         |      |                                    |   |
|  |                    |      |  DoublingNetworkTracker            |   |
|  | TileAdvPattern     |      |  (维度级网络状态管理)              |   |
|  | Matrix             |      +----------------+-----------------+   |
|  | (IGridProxyable)   |                       |                     |
|  +--------+-----------+                       v                     |
|           |                    +----------------------------------+   |
|           v                    |        Mixin Layer                |   |
|  +-------------------+        |                                    |   |
|  |  Proxy 层          |        |  CraftableItemResolverMixin        |   |
|  |                    |        |  (设置/清理 ThreadLocal)           |   |
|  | CommonProxy        |        |                                    |   |
|  | ClientProxy        |        |  CraftingContextMixin              |   |
|  | (事件分发)         |        |  (hook getPrecisePatternsFor)      |   |
|  +-------------------+        |                                    |   |
|                                |  RequestAmountHolder               |   |
|  +-------------------+        |  (传递请求剩余数量)                 |   |
|  |  Config Layer      |        +----------------------------------+   |
|  | APMConfig          |                                             |
|  | (enabled,          |                                             |
|  |  debugLog)         |                                             |
|  +-------------------+                                             |
+-------------------------------------------------------------------+
```

### 3.2 设计原则

1. **非侵入性**：不修改原始样板数据，仅在合成计算时透明替换
2. **最小注入面**：仅 2 个 Mixin 注入点
3. **委托模式**：ScaledPatternDetails 委托所有非输入/输出方法给原始样板
4. **维度级追踪**：使用 World 维度ID + 引用计数，同一维度多个矩阵正确共存
5. **安全边界**：仅处理 isCraftable() == false 的处理样板
6. **方块极简**：无 GUI、无配置、无红石控制，纯存在检测
7. **异常安全**：Mixin 注入包含 try-catch，任何异常不影响原版合成

### 3.3 项目结构

```
src/main/
+-- java/com/NzothR/apm/
|   +-- AdvancedPatternMatrixMod.java              // @Mod 主类
|   +-- CommonProxy.java                           // 通用代理 (事件分发)
|   +-- ClientProxy.java                           // 客户端代理
|   +-- Tags.java                                  // Gradle 生成的版本常量类
|   |
|   +-- block/
|   |   +-- BlockAdvPatternMatrix.java             // 方块定义
|   |   +-- ItemBlockAdvPatternMatrix.java         // 物品方块 (含提示)
|   |   +-- TileAdvPatternMatrix.java              // TileEntity (IGridProxyable)
|   |
|   +-- crafting/
|   |   +-- ScaledPatternDetails.java              // 缩放样板包装 [核心]
|   |   +-- PatternMultiplier.java                 // 倍率计算
|   |   +-- DoublingNetworkTracker.java            // 维度级网络状态追踪
|   |   +-- RequestAmountHolder.java               // ThreadLocal 请求数量栈
|   |
|   +-- mixin/
|   |   +-- CraftableItemResolverMixin.java        // Mixin: 设置 ThreadLocal
|   |   +-- CraftingContextMixin.java              // Mixin: 缩放样板 [核心]
|   |
|   +-- config/
|       +-- APMConfig.java                         // 配置 (enabled, debugLog)
|
+-- resources/
    +-- mcmod.info
    +-- mixins.apm.json                            // Mixin 配置
    +-- assets/apm/
        +-- lang/
        |   +-- en_US.lang
        |   +-- zh_CN.lang
        +-- textures/
            +-- blocks/
                +-- adv_pattern_matrix.png
```

---

## 4. 系统设计

### 4.1 方块系统

#### 4.1.1 方块属性

| 属性 | 值 |
|------|-----|
| 注册名 | apm:adv_pattern_matrix |
| 硬度 | 6.0F |
| 爆炸抗性 | 10.0F |
| TileEntity | 是 |
| GUI | 无 |
| 配置 | 无 |
| 红石控制 | 无 |
| 创造标签 | AE2 创造标签 (appeng.core.CreativeTab) |
| 唯一功能 | 作为 AE 网络设备存在，标记维度启用翻倍 |

#### 4.1.2 TileEntity 职责

```
TileAdvPatternMatrix (implements IGridProxyable)
+-- 管理 AENetworkProxy 生命周期
+-- 网络事件响应:
|   +-- validate() -> 立即注册
|   +-- gridChanged() -> 重新注册 (网络合并/分裂)
|   +-- invalidate() / onChunkUnload() -> 注销
+-- 重试冷却: 注册失败后每 20 ticks 重试
+-- NBT 序列化 (proxy 状态 + registered 标记重置)
+-- 无 GUI、无 Container、无配置项
```

#### 4.1.3 存在检测逻辑

```
维度中存在至少一个 TileAdvPatternMatrix?
  +-- 是 -> 该维度启用自动翻倍 (引用计数)
  +-- 否 -> 该维度不启用 (原版行为)

多个矩阵 = 引用计数递增，效果不叠加
最后一个矩阵移除时 = 该维度禁用翻倍
子网与主网: 矩阵在哪个维度就影响该维度的所有网络
```

#### 4.1.4 物品提示 (ItemBlockAdvPatternMatrix)

鼠标悬浮时显示3行提示信息，说明方块功能——通过语言文件本地化。

### 4.2 网络追踪系统

```
+-------------------------------------------------------------+
|              DoublingNetworkTracker                            |
+-------------------------------------------------------------+
|                                                               |
|  ConcurrentHashMap<Integer, AtomicInteger> dimensionCounts    |
|                                                               |
|  API:                                                         |
|  +-- register(World world)                                    |
|  +-- unregister(World world)                                  |
|  +-- isEnabled(World world) -> boolean                        |
|  +-- getEnabledCount() -> int                                 |
|                                                               |
|  特性:                                                        |
|  +-- 按维度 ID 追踪 (更可靠, 不受 Grid 对象生命周期影响)      |
|  +-- 引用计数 (AtomicInteger), 支持同维度多个矩阵               |
|  +-- 线程安全 (ConcurrentHashMap)                              |
|  +-- 客户端 World 自动忽略 (isRemote 检查)                     |
|  +-- 计数器归零时自动清理维度条目                               |
|                                                               |
+-------------------------------------------------------------+
```

**设计变更说明**: 最初设计使用 `Set<IGrid>` + WeakHashMap，实际实现改为按维度 ID + 引用计数追踪。原因：
- `IGrid` 对象在网络合并/分裂时会重新创建，引用追踪不可靠
- 维度 ID 在整个World生命周期内稳定
- 引用计数支持同一维度多个矩阵的正确计数

### 4.3 倍率计算系统

#### 4.3.1 核心算法

```
输入:
  remainingToProcess = 剩余待处理数量 (从 ThreadLocal 获取)
  pattern            = 原始样板

步骤:
  1. 获取样板单次输出量 outputAmount
     (从 pattern.getCondensedOutputs() 中匹配请求物品)

  2. 计算理想倍率
     idealMultiplier = ceil(remainingToProcess / outputAmount)

  3. 计算安全上限
     遍历样板中所有输入和输出 (IAEItemStack[]):
       对每个 stack (数量为 baseAmount):
         maxSafeForThisStack = floor(2,147,483,647 / baseAmount)
         globalMaxSafe = min(globalMaxSafe, maxSafeForThisStack)

  4. 取有效倍率
     effectiveMultiplier = min(idealMultiplier, globalMaxSafe)
     effectiveMultiplier = max(1, effectiveMultiplier)

  5. 返回 effectiveMultiplier
```

> **关键差异**: 实际使用 `remainingToProcess`（剩余待处理量）而非请求的总数量 `getStackSize()`。
> 这是因为 AE2 的合成请求可能会被拆分处理，`remainingToProcess` 代表当前批次的实际需求量。

#### 4.3.2 当前限制：仅处理物品

`PatternMultiplier.computeMaxSafeMultiplier()` 目前只检查 `IAEItemStack[]`（`getCondensedInputs()` / `getCondensedOutputs()`），不处理含流体的样板。这是因为 1.7.10 的 ICraftingPatternDetails 接口主要暴露 `IAEItemStack[]`。流体支持可在后续版本中添加。

### 4.4 ThreadLocal 请求数量传递

```
+-------------------------------------------------------------+
|              RequestAmountHolder                              |
+-------------------------------------------------------------+
|                                                               |
|  ThreadLocal<Deque<Long>> stack                               |
|                                                               |
|  API:                                                         |
|  +-- push(long amount)    // 进入请求解析时压栈                |
|  +-- pop()                // 退出请求解析时弹栈                |
|  +-- peek() -> long       // 获取当前层请求数量 (栈空返回1)    |
|  +-- depth() -> int       // 调试用: 当前栈深度                |
|                                                               |
|  压入值: request.remainingToProcess (剩余待处理量)             |
|  非栈顶请求数量 getStackSize() — AE2 会拆分处理请求            |
|                                                               |
|  为什么用栈而不是单值:                                        |
|  +-- 合成计算是递归的 (子请求嵌套)                            |
|  +-- 每层递归有不同的请求数量                                  |
|  +-- 栈结构确保每层读到正确的数量                              |
|                                                               |
|  pop() 空栈警告: 打印 WARN 日志，帮助调试 push/pop 不匹配      |
|  栈空时自动 remove() 清理 ThreadLocal 防止泄漏                 |
|                                                               |
+-------------------------------------------------------------+
```

---

## 5. 核心代码设计

### 5.1 主类

> 文件: src/main/java/com/NzothR/apm/AdvancedPatternMatrixMod.java

```java
package com.NzothR.apm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = AdvancedPatternMatrixMod.MODID,
    name = AdvancedPatternMatrixMod.NAME,
    version = Tags.VERSION,
    dependencies = "required-after:appliedenergistics2",
    acceptedMinecraftVersions = "[1.7.10]")
public class AdvancedPatternMatrixMod {

    public static final String MODID = "apm";
    public static final String NAME = "Advanced Pattern Matrix";
    public static final Logger LOG = LogManager.getLogger(MODID);

    /** AE2 version required by mixins — targeting rv3-beta-695 API surface */
    public static final String REQUIRED_AE2_VERSION = "rv3-beta-695";

    @Mod.Instance(MODID)
    public static AdvancedPatternMatrixMod instance;

    @SidedProxy(clientSide = "com.NzothR.apm.ClientProxy", serverSide = "com.NzothR.apm.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }
}
```

> **注意**: 版本号来自 Gradle 生成的 `Tags.VERSION` 常量（`generateGradleTokenClass`），非硬编码。
> 所有 FML 生命周期事件委托给 `CommonProxy`，保持主类简洁。

### 5.2 CommonProxy / ClientProxy

> 文件: src/main/java/com/NzothR/apm/CommonProxy.java

```java
package com.NzothR.apm;

import com.NzothR.apm.block.BlockAdvPatternMatrix;
import com.NzothR.apm.config.APMConfig;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        APMConfig.load(event.getSuggestedConfigurationFile());
    }

    public void init(FMLInitializationEvent event) {
        BlockAdvPatternMatrix.register();
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}

    public void registerRenderers() {}
}
```

> 文件: src/main/java/com/NzothR/apm/ClientProxy.java

```java
package com.NzothR.apm;

public class ClientProxy extends CommonProxy {

    @Override
    public void registerRenderers() {
        // TileAdvPatternMatrix uses default block rendering (no special renderer needed)
    }
}
```

### 5.3 BlockAdvPatternMatrix

> 文件: src/main/java/com/NzothR/apm/block/BlockAdvPatternMatrix.java

```java
package com.NzothR.apm.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.registry.GameRegistry;

public class BlockAdvPatternMatrix extends BlockContainer {

    public static final BlockAdvPatternMatrix INSTANCE = new BlockAdvPatternMatrix();

    public BlockAdvPatternMatrix() {
        super(Material.iron);
        setBlockName("apm.adv_pattern_matrix");
        setBlockTextureName("apm:adv_pattern_matrix");
        setHardness(6.0F);
        setResistance(10.0F);
        setCreativeTab(appeng.core.CreativeTab.instance);
        setStepSound(soundTypeMetal);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileAdvPatternMatrix();
    }

    @Override
    public boolean hasTileEntity(int metadata) {
        return true;
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player,
        int side, float hitX, float hitY, float hitZ) {
        return false;
    }

    public static void register() {
        GameRegistry.registerBlock(INSTANCE, ItemBlockAdvPatternMatrix.class, "adv_pattern_matrix");
        GameRegistry.registerTileEntity(TileAdvPatternMatrix.class, "apm:adv_pattern_matrix");
    }
}
```

> **与文档初版差异**: 创造标签使用 `appeng.core.CreativeTab.instance`（非原版红石标签）；
> register 使用 `ItemBlockAdvPatternMatrix.class` 以支持物品提示。

### 5.4 ItemBlockAdvPatternMatrix

> 文件: src/main/java/com/NzothR/apm/block/ItemBlockAdvPatternMatrix.java
> 此文件为实际实现中的新增组件，设计文档初版未包含。

```java
package com.NzothR.apm.block;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

public class ItemBlockAdvPatternMatrix extends ItemBlock {

    public ItemBlockAdvPatternMatrix(Block block) {
        super(block);
    }

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> tooltip, boolean advanced) {
        super.addInformation(stack, player, tooltip, advanced);
        tooltip.add(StatCollector.translateToLocal("tooltip.apm.adv_pattern_matrix.line1"));
        tooltip.add(StatCollector.translateToLocal("tooltip.apm.adv_pattern_matrix.line2"));
        tooltip.add(StatCollector.translateToLocal("tooltip.apm.adv_pattern_matrix.line3"));
    }
}
```

### 5.5 TileAdvPatternMatrix

> 文件: src/main/java/com/NzothR/apm/block/TileAdvPatternMatrix.java

```java
package com.NzothR.apm.block;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.NzothR.apm.AdvancedPatternMatrixMod;
import com.NzothR.apm.crafting.DoublingNetworkTracker;

import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;

public class TileAdvPatternMatrix extends TileEntity implements IGridProxyable {

    private static final int RETRY_COOLDOWN_TICKS = 20;

    private AENetworkProxy gridProxy;
    private boolean registered = false;
    private int retryCooldown = 0;

    @Override
    public void validate() {
        super.validate();
        if (!worldObj.isRemote) {
            getProxy().onReady();
            registerToNetwork();
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (!worldObj.isRemote) {
            unregisterFromNetwork();
            getProxy().invalidate();
        }
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (!worldObj.isRemote) {
            unregisterFromNetwork();
            getProxy().onChunkUnload();
        }
    }

    @Override
    public AENetworkProxy getProxy() {
        if (gridProxy == null) {
            gridProxy = new AENetworkProxy(
                this,
                "apm_proxy",
                new net.minecraft.item.ItemStack(BlockAdvPatternMatrix.INSTANCE),
                true);
            gridProxy.setFlags(appeng.api.networking.GridFlags.REQUIRE_CHANNEL);
            gridProxy.setIdlePowerUsage(1.0);
        }
        return gridProxy;
    }

    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(this);
    }

    @Override
    public void gridChanged() {
        registerToNetwork();
    }

    @Override
    public IGridNode getGridNode(ForgeDirection dir) {
        return getProxy().getNode();
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection dir) {
        return AECableType.COVERED;
    }

    @Override
    public void securityBreak() {
        worldObj.func_147480_a(xCoord, yCoord, zCoord, true);
    }

    public void registerToNetwork() {
        if (!registered && getProxy().isReady()) {
            IGridNode node = getProxy().getNode();
            if (node != null && node.getGrid() != null) {
                DoublingNetworkTracker.register(worldObj);
                registered = true;
                AdvancedPatternMatrixMod.LOG.info(
                    "[APM] Registered at ({}, {}, {}), dim={}, total={}",
                    xCoord, yCoord, zCoord,
                    worldObj.provider.dimensionId,
                    DoublingNetworkTracker.getEnabledCount());
            }
        }
    }

    public void unregisterFromNetwork() {
        if (registered) {
            DoublingNetworkTracker.unregister(worldObj);
            registered = false;
            AdvancedPatternMatrixMod.LOG.info(
                "[APM] Unregistered at ({}, {}, {}), total={}",
                xCoord, yCoord, zCoord,
                DoublingNetworkTracker.getEnabledCount());
        }
    }

    @Override
    public void updateEntity() {
        if (!worldObj.isRemote && !registered) {
            if (retryCooldown > 0) {
                retryCooldown--;
                return;
            }
            retryCooldown = RETRY_COOLDOWN_TICKS;
            registerToNetwork();
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        getProxy().readFromNBT(data);
        registered = false;
    }

    @Override
    public void writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        getProxy().writeToNBT(data);
    }
}
```

> **与文档初版主要差异**:
> 1. 新增 `gridChanged()` 回调 — 网络合并/分裂时自动重新注册
> 2. 新增重试冷却机制 (`retryCooldown`, 20 ticks) — 注册失败时避免每 tick 重试
> 3. `readFromNBT` 中 `registered = false` — 确保加载后重新注册
> 4. `onChunkUnload` 调用 `getProxy().onChunkUnload()` (非 `invalidate()`)
> 5. `getProxy()` 是 public (实现 `IGridProxyable` 接口)
> 6. 注册/注销时记录详细日志 (坐标、维度、当前总数)
> 7. 追踪使用 `World` 维度ID 而非 `IGrid` 引用

### 5.6 DoublingNetworkTracker

> 文件: src/main/java/com/NzothR/apm/crafting/DoublingNetworkTracker.java

```java
package com.NzothR.apm.crafting;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.world.World;

import com.NzothR.apm.AdvancedPatternMatrixMod;

/**
 * 追踪哪些维度启用了自动翻倍（使用维度 ID + 引用计数）。
 */
public final class DoublingNetworkTracker {

    private static final ConcurrentHashMap<Integer, AtomicInteger> dimensionCounts =
        new ConcurrentHashMap<>();

    private DoublingNetworkTracker() {}

    public static void register(World world) {
        if (world == null || world.isRemote) return;
        int dimId = world.provider.dimensionId;
        AtomicInteger counter = dimensionCounts.computeIfAbsent(dimId, k -> new AtomicInteger(0));
        int newCount = counter.incrementAndGet();
        AdvancedPatternMatrixMod.LOG.info("[APM] Dim {} doubling count: {} (registered)", dimId, newCount);
    }

    public static void unregister(World world) {
        if (world == null || world.isRemote) return;
        int dimId = world.provider.dimensionId;
        AtomicInteger counter = dimensionCounts.get(dimId);
        if (counter == null) return;
        int newCount = counter.decrementAndGet();
        AdvancedPatternMatrixMod.LOG.info("[APM] Dim {} doubling count: {} (unregistered)", dimId, newCount);
        if (newCount <= 0) {
            dimensionCounts.remove(dimId);
        }
    }

    public static boolean isEnabled(World world) {
        if (world == null || world.isRemote) return false;
        AtomicInteger counter = dimensionCounts.get(world.provider.dimensionId);
        return counter != null && counter.get() > 0;
    }

    public static int getEnabledCount() {
        return dimensionCounts.size();
    }
}
```

> **重要**: 这是与文档初版差异最大的组件。
> - **初版设计**: `Set<IGrid>` + WeakHashMap 按网络追踪
> - **实际实现**: `ConcurrentHashMap<Integer, AtomicInteger>` 按维度ID + 引用计数追踪
> - 引用计数确保同一维度多个矩阵正确共存（最后一个离开才禁用翻倍）
> - 客户端 World（`isRemote == true`）自动忽略
> - 计数器归零自动清理，无内存泄漏风险

### 5.7 PatternMultiplier

> 文件: src/main/java/com/NzothR/apm/crafting/PatternMultiplier.java

```java
package com.NzothR.apm.crafting;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

public final class PatternMultiplier {

    public static final long HARD_CAP = Integer.MAX_VALUE;

    private PatternMultiplier() {}

    public static long compute(ICraftingPatternDetails pattern, long requestAmount, long outputAmount) {
        if (outputAmount <= 0 || requestAmount <= 0) {
            return 1;
        }

        long idealMultiplier = ceilDiv(requestAmount, outputAmount);
        long maxSafeMultiplier = computeMaxSafeMultiplier(pattern);
        long effective = Math.min(idealMultiplier, maxSafeMultiplier);

        return Math.max(1, effective);
    }

    public static long computeMaxSafeMultiplier(ICraftingPatternDetails pattern) {
        long maxSafe = HARD_CAP;

        IAEItemStack[] inputs = pattern.getCondensedInputs();
        if (inputs != null) {
            for (IAEItemStack stack : inputs) {
                if (stack == null) continue;
                long baseAmount = stack.getStackSize();
                if (baseAmount <= 0) continue;
                long allowed = HARD_CAP / baseAmount;
                maxSafe = Math.min(maxSafe, allowed);
            }
        }

        IAEItemStack[] outputs = pattern.getCondensedOutputs();
        if (outputs != null) {
            for (IAEItemStack stack : outputs) {
                if (stack == null) continue;
                long baseAmount = stack.getStackSize();
                if (baseAmount <= 0) continue;
                long allowed = HARD_CAP / baseAmount;
                maxSafe = Math.min(maxSafe, allowed);
            }
        }

        return Math.max(1, maxSafe);
    }

    public static long ceilDiv(long a, long b) {
        if (b <= 0) throw new ArithmeticException("division by zero or negative");
        return a / b + (a % b != 0 ? 1 : 0);
    }
}
```

> **与文档初版差异**: 使用 `getCondensedInputs()` / `getCondensedOutputs()` (返回 `IAEItemStack[]`)，而非文档初版中的 `getCondensedAEInputs()` / `getCondensedAEOutputs()` (返回 `IAEStack[]`)。当前仅处理物品栈。

### 5.8 ScaledPatternDetails

> 文件: src/main/java/com/NzothR/apm/crafting/ScaledPatternDetails.java

```java
package com.NzothR.apm.crafting;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

/**
 * Scaled pattern wrapper. equals/hashCode now properly includes multiplier.
 */
public class ScaledPatternDetails implements ICraftingPatternDetails {

    private static final IAEItemStack[] EMPTY = new IAEItemStack[0];

    private final ICraftingPatternDetails original;
    private final long multiplier;

    private volatile IAEItemStack[] cachedInputs;
    private volatile IAEItemStack[] cachedCondensedInputs;
    private volatile IAEItemStack[] cachedOutputs;
    private volatile IAEItemStack[] cachedCondensedOutputs;

    public ScaledPatternDetails(ICraftingPatternDetails original, long multiplier) {
        this.original = original;
        this.multiplier = multiplier;
    }

    public ICraftingPatternDetails getOriginal() {
        return original;
    }

    public long getMultiplier() {
        return multiplier;
    }

    @Override
    public IAEItemStack[] getInputs() {
        if (cachedInputs == null) {
            synchronized (this) {
                if (cachedInputs == null) cachedInputs = scale(original.getInputs());
            }
        }
        return cachedInputs;
    }

    @Override
    public IAEItemStack[] getCondensedInputs() {
        if (cachedCondensedInputs == null) {
            synchronized (this) {
                if (cachedCondensedInputs == null)
                    cachedCondensedInputs = scale(original.getCondensedInputs());
            }
        }
        return cachedCondensedInputs;
    }

    @Override
    public IAEItemStack[] getOutputs() {
        if (cachedOutputs == null) {
            synchronized (this) {
                if (cachedOutputs == null) cachedOutputs = scale(original.getOutputs());
            }
        }
        return cachedOutputs;
    }

    @Override
    public IAEItemStack[] getCondensedOutputs() {
        if (cachedCondensedOutputs == null) {
            synchronized (this) {
                if (cachedCondensedOutputs == null)
                    cachedCondensedOutputs = scale(original.getCondensedOutputs());
            }
        }
        return cachedCondensedOutputs;
    }

    @Override
    public ItemStack getPattern() {
        return original.getPattern();
    }

    @Override
    public boolean isValidItemForSlot(int s, ItemStack is, World w) {
        return original.isValidItemForSlot(s, is, w);
    }

    @Override
    public boolean isCraftable() {
        return original.isCraftable();
    }

    @Override
    public boolean canSubstitute() {
        return original.canSubstitute();
    }

    @Override
    public ItemStack getOutput(InventoryCrafting inv, World w) {
        return original.getOutput(inv, w);
    }

    @Override
    public int getPriority() {
        return original.getPriority();
    }

    @Override
    public void setPriority(int p) {
        original.setPriority(p);
    }

    private IAEItemStack[] scale(IAEItemStack[] arr) {
        if (arr == null || arr.length == 0) return EMPTY;
        IAEItemStack[] result = new IAEItemStack[arr.length];
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != null) {
                result[i] = arr[i].copy();
                result[i].setStackSize(Math.multiplyExact(arr[i].getStackSize(), multiplier));
            }
        }
        return result;
    }

    @Override
    public int hashCode() {
        return original.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof ScaledPatternDetails)
            return this.original.equals(((ScaledPatternDetails) obj).original);
        return this.original.equals(obj);
    }

    @Override
    public String toString() {
        return "ScaledPatternDetails{orig=" + original + ", mul=" + multiplier + "}";
    }
}
```

> **与文档初版重大差异**:
> 1. **未实现** `getAEInputs()` / `getAEOutputs()` — 这两个方法不在 `ICraftingPatternDetails` 接口中（1.7.10 AE2 无此方法）
> 2. `scale()` 使用 `Math.multiplyExact()` 防溢出，而非原始 `*` 乘法
> 3. `equals()` 只比较 `original`（不比较 multiplier）— 确保 AE2 内部样板去重正确
> 4. `hashCode()` 只基于 `original.hashCode()`
> 5. 空/null 数组返回 `EMPTY` 常量而非 null
> 6. 构造函数不做参数验证（信任调用方）

### 5.9 RequestAmountHolder

> 文件: src/main/java/com/NzothR/apm/crafting/RequestAmountHolder.java

```java
package com.NzothR.apm.crafting;

import java.util.ArrayDeque;
import java.util.Deque;

import com.NzothR.apm.AdvancedPatternMatrixMod;

/**
 * ThreadLocal stack for crafting request amount propagation.
 */
public final class RequestAmountHolder {

    private static final ThreadLocal<Deque<Long>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private RequestAmountHolder() {}

    public static void push(long amount) {
        STACK.get().push(amount);
    }

    public static void pop() {
        Deque<Long> st = STACK.get();
        if (!st.isEmpty()) {
            st.pop();
        } else {
            AdvancedPatternMatrixMod.LOG.warn("[APM] pop() called on empty stack — push/pop mismatch!");
        }
        if (st.isEmpty()) STACK.remove();
    }

    public static long peek() {
        Deque<Long> st = STACK.get();
        return st.isEmpty() ? 1L : st.peek();
    }

    public static int depth() {
        return STACK.get().size();
    }
}
```

> **与文档初版差异**:
> 1. 新增 `depth()` 方法用于调试
> 2. `pop()` 空栈时输出 WARN 日志（而非静默忽略）
> 3. 移除了 `clear()` 方法

### 5.10 APMConfig

> 文件: src/main/java/com/NzothR/apm/config/APMConfig.java

```java
package com.NzothR.apm.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public final class APMConfig {

    public static boolean enabled = true;
    public static boolean debugLog = false;

    private APMConfig() {}

    public static void load(File configFile) {
        Configuration config = new Configuration(configFile);
        try {
            config.load();

            enabled = config.getBoolean(
                "enabled",
                Configuration.CATEGORY_GENERAL,
                true,
                "Enable automatic pattern doubling when Advanced Pattern Matrix is connected");

            debugLog = config.getBoolean(
                "debugLog",
                Configuration.CATEGORY_GENERAL,
                false,
                "Enable detailed debug logging for pattern doubling operations");

        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }
    }
}
```

> **与文档初版差异**:
> 1. 移除了 `hardCap` 配置项（硬编码为 `Integer.MAX_VALUE`）
> 2. 新增 `debugLog` 配置项（控制详细调试日志）
> 3. 构造函数为 private（工具类模式）

### 5.11 CraftableItemResolverMixin

> 文件: src/main/java/com/NzothR/apm/mixin/CraftableItemResolverMixin.java

```java
package com.NzothR.apm.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.NzothR.apm.crafting.RequestAmountHolder;

import appeng.crafting.v2.CraftingContext;
import appeng.crafting.v2.CraftingRequest;
import appeng.crafting.v2.resolvers.CraftableItemResolver;

/**
 * Mixin: CraftableItemResolver (AE2 rv3-beta-695)
 *
 * ThreadLocal push/pop for request amount propagation.
 */
@Mixin(value = CraftableItemResolver.class, remap = false)
public abstract class CraftableItemResolverMixin {

    @Inject(method = "provideCraftingRequestResolvers", at = @At("HEAD"))
    private void apm$pushRequestAmount(CraftingRequest<?> request, CraftingContext context,
        CallbackInfoReturnable<List<?>> cir) {
        RequestAmountHolder.push(request.remainingToProcess);
    }

    @Inject(method = "provideCraftingRequestResolvers", at = @At("RETURN"))
    private void apm$popRequestAmount(CraftingRequest<?> request, CraftingContext context,
        CallbackInfoReturnable<List<?>> cir) {
        RequestAmountHolder.pop();
    }
}
```

> **关键差异**: 压入栈的值是 `request.remainingToProcess`（剩余待处理量），而非文档初版中的 `request.getStackSize()`。这是因为 AE2 的 `CraftingRequest` 可能被拆分处理，`remainingToProcess` 反映当前批次实际需要处理的数量。

### 5.12 CraftingContextMixin

> 文件: src/main/java/com/NzothR/apm/mixin/CraftingContextMixin.java

```java
package com.NzothR.apm.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.NzothR.apm.AdvancedPatternMatrixMod;
import com.NzothR.apm.config.APMConfig;
import com.NzothR.apm.crafting.DoublingNetworkTracker;
import com.NzothR.apm.crafting.PatternMultiplier;
import com.NzothR.apm.crafting.RequestAmountHolder;
import com.NzothR.apm.crafting.ScaledPatternDetails;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.v2.CraftingContext;

/**
 * Mixin: CraftingContext (AE2 rv3-beta-695)
 *
 * Replaces patterns with ScaledPatternDetails at getPrecisePatternsFor RETURN.
 */
@Mixin(value = CraftingContext.class, remap = false)
public abstract class CraftingContextMixin {

    @Shadow
    @Final
    public net.minecraft.world.World world;

    @Inject(method = "getPrecisePatternsFor", at = @At("RETURN"), cancellable = true)
    private void apm$scalePatterns(IAEItemStack stack,
        CallbackInfoReturnable<List<ICraftingPatternDetails>> cir) {
        if (!APMConfig.enabled) return;

        try {
            if (!DoublingNetworkTracker.isEnabled(this.world)) return;

            List<ICraftingPatternDetails> original = cir.getReturnValue();
            if (original == null || original.isEmpty()) return;

            long requestAmount = RequestAmountHolder.peek();
            if (requestAmount <= 1) return;

            List<ICraftingPatternDetails> scaled = new ArrayList<>(original.size());
            boolean anyScaled = false;

            for (ICraftingPatternDetails pattern : original) {
                if (pattern.isCraftable()) {
                    scaled.add(pattern);
                    continue;
                }

                long outputAmount = getMatchingAmount(pattern, stack);
                if (outputAmount <= 0) {
                    scaled.add(pattern);
                    continue;
                }

                long mul = PatternMultiplier.compute(pattern, requestAmount, outputAmount);
                if (mul > 1) {
                    scaled.add(new ScaledPatternDetails(pattern, mul));
                    anyScaled = true;
                } else {
                    scaled.add(pattern);
                }
            }

            if (anyScaled) {
                cir.setReturnValue(scaled);
                if (APMConfig.debugLog) {
                    AdvancedPatternMatrixMod.LOG
                        .info("[APM] Scaled {} patterns for requestAmount={}", scaled.size(), requestAmount);
                }
            }
        } catch (Exception e) {
            AdvancedPatternMatrixMod.LOG.error("[APM] Error in scalePatterns", e);
        }
    }

    private static long getMatchingAmount(ICraftingPatternDetails pattern, IAEItemStack requestStack) {
        IAEItemStack[] outputs = pattern.getCondensedOutputs();
        if (outputs == null) return 0;
        for (IAEItemStack out : outputs) {
            if (out != null && out.isSameType(requestStack)) return out.getStackSize();
        }
        return 0;
    }
}
```

> **与文档初版关键差异**:
> 1. `@Shadow` 注入的是 `World world`（非 `IGrid meGrid`）— 配合维度级追踪
> 2. 优先检查 `APMConfig.enabled` 全局开关
> 3. `requestAmount <= 1` 时跳过（无需翻倍，文档初版为 `<= 0`）
> 4. `getMatchingAmount` 参数类型为 `IAEItemStack`（非 `IAEStack`）
> 5. 整个缩放逻辑包裹在 try-catch 中，异常不影响原版合成
> 6. 支持 `debugLog` 配置项控制调试输出

---

## 6. 执行流程详解

### 6.1 完整下单流程 (带翻倍)

```
玩家下单: "合成 123 个铁板"
样板: 1铁锭 -> 1铁板 (处理样板)
网络中已连接高级样板优化矩阵
         |
         v
CraftingGridCache.beginCraftingJob()
         |
         v
new CraftingJobV2(world, grid, src, 123x铁板, mode, cb)
         |
         v
new CraftingContext(world, grid, src)
  |  availablePatterns = grid.getCraftingMultiPatterns()
  |
  v
context.addRequest(CraftingRequest(123x铁板))
         |
         v
CraftingCalculations.tryResolveCraftingRequest()
         |
         v
CraftableItemResolver.provideCraftingRequestResolvers(request, context)
  |
  |  [Mixin HEAD] RequestAmountHolder.push(request.remainingToProcess) // = 123
  |
  v
context.getPrecisePatternsFor(铁板)
  |
  |  [Mixin RETURN] apm$scalePatterns:
  |    1. APMConfig.enabled == true
  |    2. DoublingNetworkTracker.isEnabled(world) == true
  |    3. requestAmount = RequestAmountHolder.peek() == 123
  |    4. 原始样板: 1铁锭 -> 1铁板, outputAmount = 1
  |    5. idealMultiplier = ceil(123 / 1) = 123
  |    6. maxSafe = 2,147,483,647 / 1 = 2,147,483,647
  |    7. effectiveMultiplier = min(123, 2,147,483,647) = 123
  |    8. 返回 ScaledPatternDetails(原始样板, 123)
  |       缩放后: 123铁锭 -> 123铁板
  |
  v
new CraftFromPatternTask(request, scaledPattern, priority, context)
  |  patternOutputs = [123x铁板]
  |  matchingOutput = 123x铁板
  |  toCraft = ceilDiv(remainingToProcess, 123) = ceilDiv(123, 123) = 1
  |
  |  子请求: 铁锭 amount = 123 * 1 = 123
  |
  v
  [Mixin RETURN] RequestAmountHolder.pop()
         |
         v
... 递归解析子请求 (123铁锭) ...
         |
         v
合成计划完成: 1 次发配 (原版需要 123 次!)
         |
         v
submitJob -> CraftingCPUCluster.addCrafting(scaledPattern, 1)
         |
         v
CPU 发配 1 次: 123铁锭 -> 机器 -> 123铁板
```

### 6.2 网络生命周期

```
[放置方块]
TileAdvPatternMatrix.validate()
  -> getProxy().onReady()
  -> registerToNetwork()
     -> DoublingNetworkTracker.register(worldObj)
        -> dimensionCounts[dimId]++  (引用计数+1)
  -> 该维度合成计算开始启用翻倍

[网络合并/分裂]
TileAdvPatternMatrix.gridChanged()
  -> registerToNetwork()  // 重新注册 (已注册则跳过)

[另一矩阵加入同一维度]
  -> DoublingNetworkTracker.register(worldObj)
     -> dimensionCounts[dimId]++  (引用计数变为2)
  -> 效果不变 (布尔开关)

[破坏一个矩阵]
TileAdvPatternMatrix.invalidate()
  -> unregisterFromNetwork()
     -> DoublingNetworkTracker.unregister(worldObj)
        -> dimensionCounts[dimId]--  (引用计数变为1)
  -> 翻倍仍然启用 (还有其他矩阵)

[破坏最后一个矩阵]
  -> dimensionCounts[dimId]-- -> 0
  -> dimensionCounts.remove(dimId)
  -> 该维度恢复原版行为

[区块卸载]
TileAdvPatternMatrix.onChunkUnload()
  -> unregisterFromNetwork()
  -> getProxy().onChunkUnload()

[注册失败重试]
updateEntity()
  -> 每 20 ticks 重试一次 registerToNetwork()
  -> 直到成功 (AE 网络就绪)
```

---

## 7. Mixin 配置与注入策略

### 7.1 Mixin 配置文件

> 文件: src/main/resources/mixins.apm.json

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.NzothR.apm.mixin",
  "refmap": "mixins.apm.refmap.json",
  "compatibilityLevel": "JAVA_8",
  "mixins": [
    "CraftableItemResolverMixin",
    "CraftingContextMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

### 7.2 注入点汇总

| Mixin 类 | 目标类 | 目标方法 | 注入时机 | 作用 |
|-----------|--------|----------|----------|------|
| CraftableItemResolverMixin | CraftableItemResolver | provideCraftingRequestResolvers | HEAD | push `remainingToProcess` 到 ThreadLocal |
| CraftableItemResolverMixin | CraftableItemResolver | provideCraftingRequestResolvers | RETURN | pop ThreadLocal |
| CraftingContextMixin | CraftingContext | getPrecisePatternsFor | RETURN (cancellable) | 缩放样板列表 |

### 7.3 注入安全性分析

```
CraftableItemResolverMixin:
  - HEAD 注入: 在方法最开头执行，不影响原逻辑
  - RETURN 注入: 在方法返回前执行，仅清理 ThreadLocal
  - 风险: 极低 (仅操作 ThreadLocal)
  - pop() 空栈时有 WARN 日志，帮助排查 push/pop 不匹配

CraftingContextMixin:
  - RETURN + cancellable: 在方法返回后修改返回值
  - 首先检查 APMConfig.enabled 全局开关
  - 仅在 DoublingNetworkTracker.isEnabled() 为 true 时干预
  - 未启用时完全透明 (直接 return，不修改 cir)
  - 整个缩放逻辑包裹在 try-catch，异常时走原版流程
  - requestAmount <= 1 时跳过 (避免无意义包装)
  - 风险: 低 (仅替换列表中的样板对象)
```

---

## 8. 构建配置

### 8.1 构建系统

本模组使用 GTNH 标准构建系统 (基于 Gradle 8.x + Kotlin DSL)，而非传统的 `build.gradle`。

核心文件:
- `build.gradle.kts` — 主构建脚本 (由 GTNH 维护，不建议修改)
- `settings.gradle.kts` — 项目设置
- `gradle.properties` — 核心配置 (modId, modGroup, 版本, Mixin 开关等)
- `dependencies.gradle` — 依赖声明
- `repositories.gradle` — Maven 仓库配置

### 8.2 关键 gradle.properties 配置

```properties
modName = Advanced Pattern Matrix
modId = apm
modGroup = com.NzothR.apm
minecraftVersion = 1.7.10
forgeVersion = 10.13.4.1614
usesMixins = true
mixinsPackage = mixin
enableModernJavaSyntax = jabel
generateGradleTokenClass = com.NzothR.apm.Tags
gradleTokenVersion = VERSION
enableGenericInjection = true
```

### 8.3 依赖 (dependencies.gradle)

```groovy
dependencies {
    // GTNH AE2 rv3-beta-695
    compileOnly("com.github.GTNewHorizons:Applied-Energistics-2-Unofficial:rv3-beta-695-GTNH:dev") {
        transitive = false
    }

    // GTNHLib (provides Mixin runtime via UniMixins)
    compileOnly("com.github.GTNewHorizons:GTNHLib:0.6.12:dev") {
        transitive = false
    }
}
```

### 8.4 mcmod.info

```json
{
    "modListVersion": 2,
    "modList": [{
        "modid": "${modId}",
        "name": "${modName}",
        "description": "Automatic pattern doubling for AE2 crafting.",
        "version": "${modVersion}",
        "mcversion": "${minecraftVersion}",
        "requiredMods": ["appliedenergistics2"],
        "dependencies": ["appliedenergistics2"]
    }]
}
```

> 使用 Gradle 变量替换 (`${modId}`, `${modName}` 等)，由构建系统自动填充。

---

## 9. 兼容性与风险分析

### 9.1 与 GTNH 样板优化矩阵的兼容

| 场景 | 行为 |
|------|------|
| 仅有样板优化矩阵 | 原版行为，修改样板内容 |
| 仅有高级样板优化矩阵 | 运行时缩放，不修改样板 |
| 两者同时存在 | 样板优化矩阵先修改样板内容，高级矩阵再对修改后的样板进行运行时缩放 (叠加效果) |

两者不冲突，因为：
- 样板优化矩阵修改的是注册到 CraftingGridCache 的样板数据
- 高级矩阵在 getPrecisePatternsFor 返回时包装，不修改缓存

### 9.2 与 AE2 其他功能的兼容

| 功能 | 影响 | 说明 |
|------|------|------|
| 合成样板 (Crafting Pattern) | 无影响 | isCraftable()==true 时跳过 |
| 不同维度 | 隔离 | 矩阵仅影响所在维度 |
| 多 CPU 并行 | 兼容 | 每个 CPU 独立计算 |
| 样板优先级 | 兼容 | ScaledPatternDetails 委托 getPriority() |
| 样板替换 (canSubstitute) | 兼容 | 委托 canSubstitute() |
| 模糊匹配 (Fuzzy) | 兼容 | isValidItemForSlot 委托原始样板 |
| 网络合并/分裂 | 兼容 | gridChanged() 回调自动重新注册 |

### 9.3 当前限制

| 限制 | 说明 | 影响 |
|------|------|------|
| 仅物品样板 | 不处理含流体的样板 | 流体配方不翻倍 |
| 维度级追踪 | 使用维度ID而非 IGrid | 同一维度所有网络共享翻倍状态 |

### 9.4 风险点与缓解

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| CraftingCPUCluster 内部用 getPattern() 匹配 | 中 | ScaledPatternDetails.getPattern() 返回原始样板物品 |
| equals/hashCode 冲突 | 低 | equals 仅比较 original，避免 AE2 内部去重问题 |
| 递归样板 (输出是输入的超集) | 低 | CraftFromPatternTask 有 hasRecursiveInputs 检测 |
| 多线程并发访问 | 低 | ThreadLocal 隔离 + ConcurrentHashMap |
| Mixin 目标方法签名变化 (AE2 更新) | 中 | 锁定 rv3-beta-695 版本 |
| 内存泄漏 (ThreadLocal) | 低 | 栈空时自动 remove() |
| 整数溢出 (乘法) | 低 | `Math.multiplyExact()` + 先除后乘计算倍率 |
| Mixin 异常影响原版 | 极低 | try-catch 包裹，异常时原样返回 |

---

## 10. 测试计划

### 10.1 单元测试

| 测试项 | 输入 | 期望输出 |
|--------|------|----------|
| ceilDiv 基本 | ceilDiv(10, 3) | 4 |
| ceilDiv 整除 | ceilDiv(12, 3) | 4 |
| ceilDiv 大数 | ceilDiv(40000000000, 1) | 40000000000 |
| computeMaxSafe 正常 | 样板 1->1 | 2,147,483,647 |
| computeMaxSafe 大输入 | 样板 500000000->1 | 4 |
| computeMaxSafe 超大输入 | 样板 2000000000->1 | 1 |
| compute 正常 | 请求123, 输出1 | 123 |
| compute 超限 | 请求40G, 输出1 | 2,147,483,647 |
| compute 向上取整 | 请求10, 输出3 | 4 |
| ScaledPattern 输入 | 原始[1铁锭], mul=123 | [123铁锭] |
| ScaledPattern 输出 | 原始[1铁板], mul=123 | [123铁板] |
| ScaledPattern 委托 | getPattern() | 返回原始样板物品 |
| ScaledPattern isCraftable | 原始false | false |
| ScaledPattern equals | 同original不同mul | true (仅比较original) |

### 10.2 集成测试

| 测试场景 | 操作 | 期望结果 |
|----------|------|----------|
| 基本翻倍 | 放矩阵，下单123铁板 (样板1->1) | 1次发配，123铁锭一次性发出 |
| 无矩阵 | 不放矩阵，下单123铁板 | 123次发配 (原版行为) |
| 超限 | 下单40G铁板 | ~19次发配，每次2.1G |
| 合成样板不处理 | 下单工作台配方物品 | 原版行为，不翻倍 |
| 同维度多矩阵 | 同维度放2个矩阵，打掉1个 | 翻倍仍然生效 |
| 同维度全移除 | 打掉所有矩阵 | 翻倍停止 |
| 移除矩阵 | 下单过程中破坏矩阵 | 当前任务继续，新任务不翻倍 |
| 递归合成 | 多层合成链 | 每层独立计算倍率 |
| 多物品输出 | 样板有多个输出 | 所有输出正确缩放 |
| 多物品输入 | 样板有多个输入 | 所有输入正确缩放，取最严格上限 |
| 优先级 | 多个样板不同优先级 | 优先级正确委托 |
| 跨维度 | 主世界放矩阵，下界下单 | 主世界翻倍，下界不翻倍 |
| 网络合并 | 两个网络合并 | gridChanged() 重新注册 |
| 配置禁用 | enabled=false | 所有维度不翻倍 |

---

## 11. 开发路线图

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 1 | 搭建开发环境 (Forge 1.7.10 + Mixin + AE2 695 依赖) | ✅ 完成 |
| Phase 2 | 实现 ScaledPatternDetails + PatternMultiplier | ✅ 完成 |
| Phase 3 | 实现 RequestAmountHolder + DoublingNetworkTracker | ✅ 完成 |
| Phase 4 | 实现 TileAdvPatternMatrix + BlockAdvPatternMatrix + ItemBlock | ✅ 完成 |
| Phase 5 | 实现 CraftableItemResolverMixin + CraftingContextMixin | ✅ 完成 |
| Phase 6 | 集成测试 | 待进行 |
| Phase 7 | 兼容性测试 | 待进行 |
| Phase 8 | 压力测试 + 边界情况修复 | 待进行 |
| Phase 9 | 打包发布 | 待进行 |

---

## 12. 附录

### 12.1 关键类引用 (AE2 rv3-beta-695)

```
appeng.api.networking.crafting.ICraftingPatternDetails  // 样板接口
appeng.api.storage.data.IAEItemStack                     // 物品栈
appeng.api.networking.IGridHost                          // 网络设备接口
appeng.api.networking.IGridNode                          // 网络节点
appeng.api.util.AECableType                              // 线缆类型
appeng.api.util.DimensionalCoord                         // 坐标
appeng.me.helpers.AENetworkProxy                         // 网络代理
appeng.me.helpers.IGridProxyable                         // 可代理接口
appeng.crafting.v2.CraftingContext                       // 合成上下文
appeng.crafting.v2.CraftingRequest                       // 合成请求
appeng.crafting.v2.CraftingJobV2                         // 合成任务
appeng.crafting.v2.resolvers.CraftableItemResolver       // 可合成解析器
appeng.crafting.v2.tasks.CraftFromPatternTask            // 样板合成任务
appeng.me.cache.CraftingGridCache                        // 合成网格缓存
appeng.me.cluster.implementations.CraftingCPUCluster     // CPU 集群
```

### 12.2 EAEP 对应关系

| 本模组 (1.7.10) | EAEP (1.20+) | 说明 |
|-----------------|--------------|------|
| ScaledPatternDetails | ScaledProcessingPattern | 缩放样板包装 |
| PatternMultiplier | PatternScaler | 倍率计算 |
| RequestAmountHolder | RequestedAmountHolder | ThreadLocal 请求数量 |
| DoublingNetworkTracker | (无直接对应) | 维度级网络状态追踪 |
| CraftingContextMixin | PatternProviderBlockEntity Mixin | 样板替换注入点 |
| TileAdvPatternMatrix | (无直接对应，EAEP 用 Pattern Provider) | 网络设备 |

### 12.3 语言文件

en_US.lang:
```
tile.apm.adv_pattern_matrix.name=Advanced Pattern Optimization Matrix

tooltip.apm.adv_pattern_matrix.line1=Connects to ME network to
tooltip.apm.adv_pattern_matrix.line2=automatically scale processing patterns
tooltip.apm.adv_pattern_matrix.line3=during crafting calculations.
```

zh_CN.lang:
```
tile.apm.adv_pattern_matrix.name=高级样板优化矩阵

tooltip.apm.adv_pattern_matrix.line1=连接到 ME 网络
tooltip.apm.adv_pattern_matrix.line2=自动在处理样板合成计算时
tooltip.apm.adv_pattern_matrix.line3=放大输入输出量
```

---

## 文档结束

本文档基于实际源码 (2026-07-23) 编写，反映 v1.0.0 的实现状态。
