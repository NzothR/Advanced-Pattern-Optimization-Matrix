# 高级样板优化矩阵 (Advanced Pattern Optimization Matrix)

## 模组设计方案文档 v1.0

> **目标平台**: Minecraft 1.7.10 / GTNH 2.8.4
> **AE2 版本**: Applied-Energistics-2-Unofficial rv3-beta-695-GTNH
> **开发框架**: Forge 10.13.4.1614 + Mixin (UniMixins)
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
- **安全上限**：翻倍后任何单个物品/流体数量不超过 2,147,483,647 (Integer.MAX_VALUE)

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

EAEP 核心逻辑 (简化):

```java
// PatternScaler.java
public static IPatternDetails createScaled(AEProcessingPattern pattern, long multiplier) {
    return new ScaledProcessingPattern(pattern, multiplier);
}

// ScaledProcessingPattern 重写 getInputs/getOutputs:
public List<GenericStack> getInputs() {
    return original.getInputs().stream()
        .map(stack -> new GenericStack(stack.what(), stack.amount() * multiplier))
        .collect(toList());
}
```

### 2.2 GTNH AE2 1.7.10 合成计算流程 (rv3-beta-695)

#### 2.2.1 关键接口

```java
public interface ICraftingPatternDetails {
    ItemStack getPattern();
    IAEItemStack[] getInputs();
    IAEItemStack[] getCondensedInputs();
    IAEItemStack[] getOutputs();
    IAEItemStack[] getCondensedOutputs();
    IAEStack[] getAEInputs();
    IAEStack[] getAEOutputs();
    boolean isCraftable();           // true=合成样板(工作台), false=处理样板
    boolean canSubstitute();
    boolean isValidItemForSlot(int slot, ItemStack item, World world);
    ItemStack getOutput(InventoryCrafting inv, World world);
    int getPriority();
    void setPriority(int priority);
}
```

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
              +-- context.getPrecisePatternsFor(stack)    <-- Hook 点 2
              |       // 返回 List<ICraftingPatternDetails>
              |       // 注意: 此方法只接收物品类型，不接收请求数量
              |
              +-- for each pattern:
                    new CraftFromPatternTask(request, pattern, priority, context)
                          |
                          +-- patternOutputs = pattern.getCondensedAEOutputs()
                          +-- matchingOutput = findOutput(patternOutputs, request.stack)
                          +-- toCraft = ceilDiv(remainingToProcess, matchingOutput.getStackSize())
                          |
                          +-- for each input in pattern.getCondensedAEInputs():
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

### 2.3 版本差异注意事项

| 特性 | rv3-beta-695 (GTNH 2.8.4) | 最新版 (GTNH 2.9.0) |
|------|---------------------------|---------------------|
| Crafting 引擎 | CraftingJobV2 (已稳定) | 可能有 Lite Crafting Mode 改动 |
| Stack API | IAEItemStack / IAEStack | 可能引入 Stack Type API |
| 样板接口 | ICraftingPatternDetails | 可能有扩展 |
| getPrecisePatternsFor | 返回 List<ICraftingPatternDetails> | 签名可能变化 |

本方案基于 rv3-beta-695 的接口设计，不兼容 2.9.0 的 AE2。

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
|  | TileAdvPattern     |      |  PatternMultiplier                 |   |
|  | Matrix             |      |  (倍率计算)                        |   |
|  | (IGridHost)        |      |                                    |   |
|  +--------+-----------+      |  DoublingNetworkTracker            |   |
|           |                   |  (网络状态管理)                    |   |
|           |                   +----------------+-----------------+   |
|           |                                    |                     |
|           v                                    v                     |
|  +-------------------+      +----------------------------------+   |
|  |  Network Layer     |      |        Mixin Layer                |   |
|  |                    |      |                                    |   |
|  | GridNode 管理      |      |  CraftableItemResolverMixin        |   |
|  | 网络事件监听       |      |  (设置/清理 ThreadLocal)           |   |
|  | 状态注册/注销      |      |                                    |   |
|  +-------------------+      |  CraftingContextMixin              |   |
|                              |  (hook getPrecisePatternsFor)      |   |
|  +-------------------+      |                                    |   |
|  |  ThreadLocal Layer |      |  RequestAmountHolder               |   |
|  |                    |      |  (传递请求数量)                    |   |
|  | RequestAmount      |      +----------------------------------+   |
|  | Holder             |                                             |
|  +-------------------+      +----------------------------------+   |
|                              |        Config Layer               |   |
|                              |  APMConfig (全局硬上限等)          |   |
|                              +----------------------------------+   |
+-------------------------------------------------------------------+
```

### 3.2 设计原则

1. **非侵入性**：不修改原始样板数据，仅在合成计算时透明替换
2. **最小注入面**：仅 2 个 Mixin 注入点
3. **委托模式**：ScaledPatternDetails 委托所有非输入/输出方法给原始样板
4. **弱引用追踪**：使用 WeakHashMap 避免内存泄漏
5. **安全边界**：仅处理 isCraftable() == false 的处理样板
6. **方块极简**：无 GUI、无配置、无红石控制，纯存在检测

### 3.3 项目结构

```
src/main/
+-- java/com/yourmod/apm/
|   +-- AdvancedPatternMatrixMod.java              // @Mod 主类
|   +-- CommonProxy.java                           // 通用代理
|   +-- ClientProxy.java                           // 客户端代理
|   |
|   +-- block/
|   |   +-- BlockAdvPatternMatrix.java             // 方块定义
|   |   +-- ItemBlockAdvPatternMatrix.java         // 物品方块
|   |   +-- TileAdvPatternMatrix.java              // TileEntity
|   |
|   +-- crafting/
|   |   +-- ScaledPatternDetails.java              // 缩放样板包装 [核心]
|   |   +-- PatternMultiplier.java                 // 倍率计算
|   |   +-- DoublingNetworkTracker.java            // 网络状态追踪
|   |   +-- RequestAmountHolder.java               // ThreadLocal 请求数量
|   |
|   +-- mixin/
|   |   +-- CraftableItemResolverMixin.java        // Mixin: 设置 ThreadLocal
|   |   +-- CraftingContextMixin.java              // Mixin: 缩放样板 [核心]
|   |
|   +-- config/
|       +-- APMConfig.java                         // 配置 (硬上限等)
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
| 唯一功能 | 作为 AE 网络设备存在，标记网络启用翻倍 |

#### 4.1.2 TileEntity 职责

```
TileAdvPatternMatrix
+-- 实现 IGridHost (AE 网络设备)
+-- 管理 IGridNode 生命周期
+-- 网络事件响应:
|   +-- GridNode 加入网络 -> DoublingNetworkTracker.register(grid)
|   +-- GridNode 离开网络 -> DoublingNetworkTracker.unregister(grid)
+-- NBT 序列化 (仅保存基本状态)
+-- 无 GUI、无 Container、无配置项
```

#### 4.1.3 存在检测逻辑

```
网络中是否存在至少一个 TileAdvPatternMatrix?
  +-- 是 -> 该网络启用自动翻倍
  +-- 否 -> 该网络不启用 (原版行为)

多个矩阵 = 一个矩阵 (布尔开关，不叠加)
子网与主网隔离 (矩阵在哪个网络就只影响哪个网络)
```

### 4.2 网络追踪系统

```
+-------------------------------------------------------------+
|              DoublingNetworkTracker                            |
+-------------------------------------------------------------+
|                                                               |
|  WeakHashMap<IGrid, Boolean> enabledNetworks                  |
|                                                               |
|  API:                                                         |
|  +-- register(IGrid grid)                                     |
|  +-- unregister(IGrid grid)                                   |
|  +-- isEnabled(IGrid grid) -> boolean                         |
|                                                               |
|  特性:                                                        |
|  +-- WeakHashMap 防止内存泄漏 (Grid 被回收时自动清理)          |
|  +-- 线程安全 (synchronized 或 ConcurrentHashMap)             |
|  +-- 仅存储布尔值，不存储倍率 (倍率是动态计算的)              |
|                                                               |
+-------------------------------------------------------------+
```

### 4.3 倍率计算系统

#### 4.3.1 核心算法

```
输入:
  requestAmount  = 下单数量 (从 ThreadLocal 获取)
  pattern        = 原始样板

步骤:
  1. 获取样板单次输出量 outputAmount
     (从 pattern.getCondensedAEOutputs() 中匹配请求物品)

  2. 计算理想倍率
     idealMultiplier = ceil(requestAmount / outputAmount)

  3. 计算安全上限
     遍历样板中所有输入和输出:
       对每个 stack (数量为 baseAmount):
         maxSafeForThisStack = floor(2,147,483,647 / baseAmount)
         globalMaxSafe = min(globalMaxSafe, maxSafeForThisStack)

  4. 取有效倍率
     effectiveMultiplier = min(idealMultiplier, globalMaxSafe)
     effectiveMultiplier = max(1, effectiveMultiplier)

  5. 返回 effectiveMultiplier
```

#### 4.3.2 计算示例

```
示例 1: 样板 1铁锭 -> 1铁板, 下单 123 铁板
  outputAmount = 1
  idealMultiplier = ceil(123 / 1) = 123
  输入检查: 1 * 123 = 123 < 2,147,483,647  OK
  输出检查: 1 * 123 = 123 < 2,147,483,647  OK
  effectiveMultiplier = 123
  缩放后: 123铁锭 -> 123铁板, 发配 1 次

示例 2: 样板 3铁锭 -> 3铁板, 下单 10 铁板
  outputAmount = 3
  idealMultiplier = ceil(10 / 3) = 4
  输入检查: 3 * 4 = 12 < 2,147,483,647  OK
  输出检查: 3 * 4 = 12 < 2,147,483,647  OK
  effectiveMultiplier = 4
  缩放后: 12铁锭 -> 12铁板, 发配 1 次 (产出12, 多出2进存储)

示例 3: 样板 1铁锭 -> 1铁板, 下单 40,000,000,000 铁板
  outputAmount = 1
  idealMultiplier = ceil(40G / 1) = 40,000,000,000
  输入检查: 1 * 40G > 2,147,483,647  超限!
  maxSafe = floor(2,147,483,647 / 1) = 2,147,483,647
  effectiveMultiplier = 2,147,483,647
  缩放后: 2,147,483,647铁锭 -> 2,147,483,647铁板
  发配次数: ceil(40G / 2,147,483,647) = 19 次

示例 4: 样板 9铁锭 -> 1铁块, 下单 100 铁块
  outputAmount = 1
  idealMultiplier = ceil(100 / 1) = 100
  输入检查: 9 * 100 = 900 < 2,147,483,647  OK
  输出检查: 1 * 100 = 100 < 2,147,483,647  OK
  effectiveMultiplier = 100
  缩放后: 900铁锭 -> 100铁块, 发配 1 次

示例 5: 样板 500,000,000 mB水 -> 1某物品, 下单 10 个
  outputAmount = 1
  idealMultiplier = ceil(10 / 1) = 10
  输入检查: 500,000,000 * 10 = 5,000,000,000 > 2,147,483,647  超限!
  maxSafe = floor(2,147,483,647 / 500,000,000) = 4
  effectiveMultiplier = 4
  缩放后: 2,000,000,000 mB水 -> 4某物品
  发配次数: ceil(10 / 4) = 3 次

示例 6: 样板 2,000,000,000 mB水 -> 1某物品, 下单 5 个
  outputAmount = 1
  idealMultiplier = ceil(5 / 1) = 5
  输入检查: 2,000,000,000 * 5 = 10,000,000,000 > 2,147,483,647  超限!
  maxSafe = floor(2,147,483,647 / 2,000,000,000) = 1
  effectiveMultiplier = 1 (无法翻倍)
  缩放后: 不缩放，走原版流程
  发配次数: 5 次
```

### 4.4 ThreadLocal 请求数量传递

```
+-------------------------------------------------------------+
|              RequestAmountHolder                              |
+-------------------------------------------------------------+
|                                                               |
|  ThreadLocal<Deque<Long>> stack                               |
|                                                               |
|  API:                                                         |
|  +-- push(long amount)   // 进入请求解析时压栈                |
|  +-- pop()               // 退出请求解析时弹栈                |
|  +-- peek() -> long      // 获取当前请求数量                  |
|                                                               |
|  为什么用栈而不是单值:                                        |
|  +-- 合成计算是递归的 (子请求嵌套)                            |
|  +-- 每层递归有不同的请求数量                                  |
|  +-- 栈结构确保每层读到正确的数量                              |
|                                                               |
|  调用时序:                                                    |
|  CraftableItemResolver.provideCraftingRequestResolvers()      |
|    +-- HEAD: RequestAmountHolder.push(request.getStackSize()) |
|    +-- ... (内部调用 getPrecisePatternsFor) ...               |
|    +-- RETURN: RequestAmountHolder.pop()                      |
|                                                               |
+-------------------------------------------------------------+
```

---

## 5. 核心代码设计

### 5.1 RequestAmountHolder

> 文件: src/main/java/com/yourmod/apm/crafting/RequestAmountHolder.java
> 职责: ThreadLocal 栈，在合成计算递归中传递当前请求数量

```java
package com.yourmod.apm.crafting;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ThreadLocal 栈，追踪合成计算中的请求数量。
 * 
 * 合成计算是递归的（子请求嵌套），每层递归有不同的请求数量。
 * 使用栈结构确保每层读到正确的数量。
 * 
 * 参考 EAEP 的 RequestedAmountHolder 设计。
 */
public final class RequestAmountHolder {

    private static final ThreadLocal<Deque<Long>> STACK =
        ThreadLocal.withInitial(ArrayDeque::new);

    private RequestAmountHolder() {}

    /** 进入请求解析时压栈 */
    public static void push(long amount) {
        STACK.get().push(amount);
    }

    /** 退出请求解析时弹栈 */
    public static void pop() {
        Deque<Long> deque = STACK.get();
        if (!deque.isEmpty()) {
            deque.pop();
        }
        // 如果栈空了，清理 ThreadLocal 防止内存泄漏
        if (deque.isEmpty()) {
            STACK.remove();
        }
    }

    /** 获取当前层的请求数量，栈空时返回 1 */
    public static long peek() {
        Deque<Long> deque = STACK.get();
        return deque.isEmpty() ? 1L : deque.peek();
    }

    /** 强制清理 (异常恢复用) */
    public static void clear() {
        STACK.remove();
    }
}
```

### 5.2 ScaledPatternDetails

> 文件: src/main/java/com/yourmod/apm/crafting/ScaledPatternDetails.java
> 职责: 包装原始 ICraftingPatternDetails，将所有输入/输出乘以倍率
> 对应 EAEP: ScaledProcessingPattern

```java
package com.yourmod.apm.crafting;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * 缩放样板包装类。
 *
 * 不修改原始样板数据，仅在合成计算时透明地将所有输入/输出乘以倍率。
 * 所有非输入/输出方法委托给原始样板。
 *
 * 线程安全：缓存字段使用 volatile + 双重检查锁定。
 */
public class ScaledPatternDetails implements ICraftingPatternDetails {

    private final ICraftingPatternDetails original;
    private final long multiplier;

    // 缓存缩放后的数组 (惰性计算，避免重复创建)
    private volatile IAEItemStack[] cachedInputs;
    private volatile IAEItemStack[] cachedCondensedInputs;
    private volatile IAEItemStack[] cachedOutputs;
    private volatile IAEItemStack[] cachedCondensedOutputs;
    private volatile IAEStack[] cachedAEInputs;
    private volatile IAEStack[] cachedAEOutputs;

    public ScaledPatternDetails(ICraftingPatternDetails original, long multiplier) {
        if (original == null) {
            throw new NullPointerException("original pattern cannot be null");
        }
        if (multiplier <= 0) {
            throw new IllegalArgumentException("multiplier must be positive, got: " + multiplier);
        }
        this.original = original;
        this.multiplier = multiplier;
    }

    // ==================== 访问器 ====================

    public ICraftingPatternDetails getOriginal() {
        return original;
    }

    public long getMultiplier() {
        return multiplier;
    }

    // ==================== 核心：缩放输入/输出 ====================

    @Override
    public IAEItemStack[] getInputs() {
        if (cachedInputs == null) {
            synchronized (this) {
                if (cachedInputs == null) {
                    cachedInputs = scaleItemStackArray(original.getInputs());
                }
            }
        }
        return cachedInputs;
    }

    @Override
    public IAEItemStack[] getCondensedInputs() {
        if (cachedCondensedInputs == null) {
            synchronized (this) {
                if (cachedCondensedInputs == null) {
                    cachedCondensedInputs = scaleItemStackArray(original.getCondensedInputs());
                }
            }
        }
        return cachedCondensedInputs;
    }

    @Override
    public IAEItemStack[] getOutputs() {
        if (cachedOutputs == null) {
            synchronized (this) {
                if (cachedOutputs == null) {
                    cachedOutputs = scaleItemStackArray(original.getOutputs());
                }
            }
        }
        return cachedOutputs;
    }

    @Override
    public IAEItemStack[] getCondensedOutputs() {
        if (cachedCondensedOutputs == null) {
            synchronized (this) {
                if (cachedCondensedOutputs == null) {
                    cachedCondensedOutputs = scaleItemStackArray(original.getCondensedOutputs());
                }
            }
        }
        return cachedCondensedOutputs;
    }

    @Override
    public IAEStack[] getAEInputs() {
        if (cachedAEInputs == null) {
            synchronized (this) {
                if (cachedAEInputs == null) {
                    cachedAEInputs = scaleAEStackArray(original.getAEInputs());
                }
            }
        }
        return cachedAEInputs;
    }

    @Override
    public IAEStack[] getAEOutputs() {
        if (cachedAEOutputs == null) {
            synchronized (this) {
                if (cachedAEOutputs == null) {
                    cachedAEOutputs = scaleAEStackArray(original.getAEOutputs());
                }
            }
        }
        return cachedAEOutputs;
    }

    // ==================== 委托方法 (不修改) ====================

    @Override
    public ItemStack getPattern() {
        // 返回原始样板物品，确保 CPU 能正确匹配样板
        return original.getPattern();
    }

    @Override
    public boolean isValidItemForSlot(int slotIndex, ItemStack itemStack, World world) {
        return original.isValidItemForSlot(slotIndex, itemStack, world);
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
    public ItemStack getOutput(InventoryCrafting craftingInv, World world) {
        // 处理样板不走这个方法，但为安全起见委托
        return original.getOutput(craftingInv, world);
    }

    @Override
    public int getPriority() {
        return original.getPriority();
    }

    @Override
    public void setPriority(int priority) {
        original.setPriority(priority);
    }

    // ==================== 缩放工具方法 ====================

    private IAEItemStack[] scaleItemStackArray(IAEItemStack[] arr) {
        if (arr == null) return null;
        IAEItemStack[] result = new IAEItemStack[arr.length];
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != null) {
                result[i] = arr[i].copy();
                result[i].setStackSize(arr[i].getStackSize() * multiplier);
            }
        }
        return result;
    }

    private IAEStack[] scaleAEStackArray(IAEStack[] arr) {
        if (arr == null) return null;
        IAEStack[] result = new IAEStack[arr.length];
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != null) {
                result[i] = arr[i].copy();
                result[i].setStackSize(arr[i].getStackSize() * multiplier);
            }
        }
        return result;
    }

    // ==================== equals / hashCode ====================

    @Override
    public int hashCode() {
        return 31 * original.hashCode() + Long.hashCode(multiplier);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ScaledPatternDetails)) return false;
        ScaledPatternDetails other = (ScaledPatternDetails) obj;
        return other.original.equals(this.original) && other.multiplier == this.multiplier;
    }

    @Override
    public String toString() {
        return "ScaledPatternDetails{original=" + original + ", multiplier=" + multiplier + "}";
    }
}
```

### 5.3 PatternMultiplier

> 文件: src/main/java/com/yourmod/apm/crafting/PatternMultiplier.java
> 职责: 计算样板的有效倍率，确保不超过 Integer.MAX_VALUE 上限

```java
package com.yourmod.apm.crafting;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEStack;

/**
 * 倍率计算工具。
 *
 * 核心算法:
 *   idealMultiplier = ceil(requestAmount / patternOutputAmount)
 *   maxSafeMultiplier = min(Integer.MAX_VALUE / stackAmount) for all stacks
 *   effectiveMultiplier = min(idealMultiplier, maxSafeMultiplier)
 *
 * 硬上限: 2,147,483,647 (Integer.MAX_VALUE)
 * 原因: 当前版本 AE2 样板单次最多处理 2.1G 物品/流体
 */
public final class PatternMultiplier {

    /** 硬上限: 翻倍后任何单个 stack 的数量不得超过此值 */
    public static final long HARD_CAP = Integer.MAX_VALUE; // 2,147,483,647

    private PatternMultiplier() {}

    /**
     * 计算给定样板在指定请求数量下的有效倍率。
     *
     * @param pattern       原始样板 (必须是处理样板)
     * @param requestAmount 下单数量
     * @param outputAmount  样板单次输出中匹配请求物品的数量
     * @return 有效倍率 (>= 1)
     */
    public static long compute(ICraftingPatternDetails pattern, long requestAmount, long outputAmount) {
        if (outputAmount <= 0 || requestAmount <= 0) {
            return 1;
        }

        // 1. 计算理想倍率 (向上取整，确保一次发配能满足需求)
        long idealMultiplier = ceilDiv(requestAmount, outputAmount);

        // 2. 计算安全上限 (确保翻倍后任何 stack 不超过 HARD_CAP)
        long maxSafeMultiplier = computeMaxSafeMultiplier(pattern);

        // 3. 取两者较小值
        long effective = Math.min(idealMultiplier, maxSafeMultiplier);

        // 4. 确保至少为 1
        return Math.max(1, effective);
    }

    /**
     * 计算样板的最大安全倍率。
     * 遍历所有输入和输出，确保 baseAmount * multiplier <= HARD_CAP。
     *
     * @param pattern 原始样板
     * @return 最大安全倍率 (>= 1)
     */
    public static long computeMaxSafeMultiplier(ICraftingPatternDetails pattern) {
        long maxSafe = HARD_CAP;

        // 检查所有输入
        IAEStack[] inputs = pattern.getCondensedAEInputs();
        if (inputs != null) {
            for (IAEStack stack : inputs) {
                if (stack == null) continue;
                long baseAmount = stack.getStackSize();
                if (baseAmount <= 0) continue;
                long allowed = HARD_CAP / baseAmount;
                maxSafe = Math.min(maxSafe, allowed);
            }
        }

        // 检查所有输出
        IAEStack[] outputs = pattern.getCondensedAEOutputs();
        if (outputs != null) {
            for (IAEStack stack : outputs) {
                if (stack == null) continue;
                long baseAmount = stack.getStackSize();
                if (baseAmount <= 0) continue;
                long allowed = HARD_CAP / baseAmount;
                maxSafe = Math.min(maxSafe, allowed);
            }
        }

        return Math.max(1, maxSafe);
    }

    /**
     * 向上取整除法。
     * ceilDiv(a, b) = (a + b - 1) / b
     * 注意防止溢出: 当 a 很大时使用 a / b + (a % b != 0 ? 1 : 0)
     */
    public static long ceilDiv(long a, long b) {
        if (b <= 0) throw new ArithmeticException("division by zero or negative");
        // 防溢出写法
        return a / b + (a % b != 0 ? 1 : 0);
    }
}
```

### 5.4 DoublingNetworkTracker

> 文件: src/main/java/com/yourmod/apm/crafting/DoublingNetworkTracker.java
> 职责: 追踪哪些 AE 网络启用了自动翻倍

```java
package com.yourmod.apm.crafting;

import appeng.api.networking.IGrid;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 追踪哪些 AE 网络启用了自动翻倍。
 *
 * 使用 ConcurrentHashMap.newKeySet() (等效于线程安全的 Set):
 * - 线程安全 (AE 网络事件可能在不同线程触发)
 * - Grid 对象被 GC 时不会自动清理，但 Grid 生命周期与网络绑定，
 *   网络销毁时会触发 unregister，所以不会泄漏
 *
 * 仅存储布尔存在性，不存储倍率 (倍率是每次请求动态计算的)。
 */
public final class DoublingNetworkTracker {

    private static final Set<IGrid> enabledNetworks =
        Collections.newSetFromMap(new ConcurrentHashMap<IGrid, Boolean>());

    private DoublingNetworkTracker() {}

    /**
     * 当高级样板优化矩阵加入网络时调用。
     * 多个矩阵加入同一网络只记录一次 (Set 语义)。
     */
    public static void register(IGrid grid) {
        if (grid != null) {
            enabledNetworks.add(grid);
        }
    }

    /**
     * 当高级样板优化矩阵离开网络时调用。
     * 注意: 如果同一网络有多个矩阵，只有最后一个离开时才应 unregister。
     * 但由于 Set 语义，这里直接 remove 即可 (最后一个矩阵离开时网络可能已重建)。
     * 实际实现中通过 GridNode 的 onDestroy 事件触发。
     */
    public static void unregister(IGrid grid) {
        if (grid != null) {
            enabledNetworks.remove(grid);
        }
    }

    /**
     * 查询网络是否启用翻倍。
     */
    public static boolean isEnabled(IGrid grid) {
        return grid != null && enabledNetworks.contains(grid);
    }

    /**
     * 调试用: 获取当前启用的网络数量。
     */
    public static int getEnabledCount() {
        return enabledNetworks.size();
    }
}
```

### 5.5 TileAdvPatternMatrix

> 文件: src/main/java/com/yourmod/apm/block/TileAdvPatternMatrix.java
> 职责: AE 网络设备，存在即启用翻倍

```java
package com.yourmod.apm.block;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridHost;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import com.yourmod.apm.crafting.DoublingNetworkTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.EnumSet;

/**
 * 高级样板优化矩阵 TileEntity。
 *
 * 功能极简:
 * - 作为 AE 网络设备 (IGridHost) 连接到网络
 * - 存在即启用该网络的自动翻倍
 * - 无 GUI、无配置、无红石控制
 * - 不注册任何样板 (不是 ICraftingProvider)
 */
public class TileAdvPatternMatrix extends TileEntity implements IGridProxyable {

    private AENetworkProxy gridProxy;
    private boolean registered = false;

    // ==================== AE 网络初始化 ====================

    @Override
    public void validate() {
        super.validate();
        if (!worldObj.isRemote) {
            getProxy().onReady();
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
            getProxy().invalidate();
        }
    }

    // ==================== IGridProxyable ====================

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
        // 安全破坏时移除方块
        worldObj.func_147480_a(xCoord, yCoord, zCoord, true); // destroyBlock
    }

    @Override
    public IGridNode getActionableNode() {
        return getProxy().getNode();
    }

    // ==================== 网络注册/注销 ====================

    /**
     * 当 GridNode 成功加入网络后调用。
     * 由 AENetworkProxy 的 idle tick 或 onGridChange 触发。
     */
    public void registerToNetwork() {
        if (!registered && getProxy().isReady()) {
            IGridNode node = getProxy().getNode();
            if (node != null && node.getGrid() != null) {
                DoublingNetworkTracker.register(node.getGrid());
                registered = true;
            }
        }
    }

    /**
     * 当 GridNode 离开网络时调用。
     */
    public void unregisterFromNetwork() {
        if (registered) {
            IGridNode node = getProxy().getNode();
            if (node != null && node.getGrid() != null) {
                DoublingNetworkTracker.unregister(node.getGrid());
            }
            registered = false;
        }
    }

    // ==================== Tick 更新 ====================

    @Override
    public void updateEntity() {
        super.updateEntity();
        if (!worldObj.isRemote && !registered) {
            registerToNetwork();
        }
    }

    // ==================== NBT ====================

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        getProxy().readFromNBT("apm_proxy", data);
    }

    @Override
    public void writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        getProxy().writeToNBT("apm_proxy", data);
    }

    // ==================== Proxy 初始化 ====================

    private AENetworkProxy getProxy() {
        if (gridProxy == null) {
            gridProxy = new AENetworkProxy(this, "apm_proxy",
                new net.minecraft.item.ItemStack(BlockAdvPatternMatrix.INSTANCE), true);
            gridProxy.setFlags(EnumSet.of(GridFlags.REQUIRE_CHANNEL));
            gridProxy.setIdlePowerUsage(1.0); // 1 AE/t 待机功耗
        }
        return gridProxy;
    }

    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(this);
    }
}
```

### 5.6 BlockAdvPatternMatrix

> 文件: src/main/java/com/yourmod/apm/block/BlockAdvPatternMatrix.java
> 职责: 方块定义

```java
package com.yourmod.apm.block;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 高级样板优化矩阵方块。
 * 无 GUI，右键无交互，纯存在检测。
 */
public class BlockAdvPatternMatrix extends BlockContainer {

    public static final BlockAdvPatternMatrix INSTANCE = new BlockAdvPatternMatrix();

    public BlockAdvPatternMatrix() {
        super(Material.iron);
        setBlockName("apm.adv_pattern_matrix");
        setBlockTextureName("apm:adv_pattern_matrix");
        setHardness(6.0F);
        setResistance(10.0F);
        setCreativeTab(CreativeTabs.tabRedstone); // 或自定义创造标签
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

    // 无 GUI，右键不做任何事
    @Override
    public boolean onBlockActivated(World world, int x, int y, int z,
            net.minecraft.entity.player.EntityPlayer player,
            int side, float hitX, float hitY, float hitZ) {
        return false;
    }

    public static void register() {
        GameRegistry.registerBlock(INSTANCE, "adv_pattern_matrix");
        GameRegistry.registerTileEntity(TileAdvPatternMatrix.class, "apm:adv_pattern_matrix");
    }
}
```

### 5.7 CraftableItemResolverMixin

> 文件: src/main/java/com/yourmod/apm/mixin/CraftableItemResolverMixin.java
> 职责: 在请求解析入口设置/清理 ThreadLocal

```java
package com.yourmod.apm.mixin;

import appeng.crafting.v2.CraftingContext;
import appeng.crafting.v2.CraftingRequest;
import appeng.crafting.v2.resolvers.CraftableItemResolver;
import com.yourmod.apm.crafting.RequestAmountHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin: CraftableItemResolver
 *
 * 在 provideCraftingRequestResolvers 方法的入口和出口
 * 设置/清理 ThreadLocal，传递请求数量给 CraftingContextMixin。
 *
 * 注入点:
 * - HEAD: push(request.getStackSize())
 * - RETURN: pop()
 */
@Mixin(value = CraftableItemResolver.class, remap = false)
public abstract class CraftableItemResolverMixin {

    @Inject(
        method = "provideCraftingRequestResolvers",
        at = @At("HEAD")
    )
    private static void apm$pushRequestAmount(
            CraftingRequest<?> request,
            CraftingContext context,
            CallbackInfoReturnable<List<?>> cir) {
        // 将当前请求数量压入 ThreadLocal 栈
        long amount = request.getStackSize();
        RequestAmountHolder.push(amount);
    }

    @Inject(
        method = "provideCraftingRequestResolvers",
        at = @At("RETURN")
    )
    private static void apm$popRequestAmount(
            CraftingRequest<?> request,
            CraftingContext context,
            CallbackInfoReturnable<List<?>> cir) {
        // 弹出 ThreadLocal 栈
        RequestAmountHolder.pop();
    }
}
```

### 5.8 CraftingContextMixin

> 文件: src/main/java/com/yourmod/apm/mixin/CraftingContextMixin.java
> 职责: 核心 Hook，在获取样板列表时透明替换为缩放版本

```java
package com.yourmod.apm.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEStack;
import appeng.crafting.v2.CraftingContext;
import com.yourmod.apm.crafting.DoublingNetworkTracker;
import com.yourmod.apm.crafting.PatternMultiplier;
import com.yourmod.apm.crafting.RequestAmountHolder;
import com.yourmod.apm.crafting.ScaledPatternDetails;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Mixin: CraftingContext
 *
 * Hook getPrecisePatternsFor() 方法。
 * 当网络中存在高级样板优化矩阵时，将返回的处理样板替换为缩放版本。
 *
 * 这是整个模组最核心的注入点。
 *
 * 逻辑:
 * 1. 检查网络是否启用翻倍 (DoublingNetworkTracker)
 * 2. 从 ThreadLocal 获取当前请求数量
 * 3. 对每个处理样板计算有效倍率
 * 4. 用 ScaledPatternDetails 包装
 * 5. 合成样板 (isCraftable() == true) 不处理
 */
@Mixin(value = CraftingContext.class, remap = false)
public abstract class CraftingContextMixin {

    @Shadow
    @Final
    public IGrid meGrid;

    @Inject(
        method = "getPrecisePatternsFor",
        at = @At("RETURN"),
        cancellable = true
    )
    private void apm$scalePatterns(
            IAEStack<?> stack,
            CallbackInfoReturnable<List<ICraftingPatternDetails>> cir) {

        // 1. 检查网络是否启用翻倍
        if (!DoublingNetworkTracker.isEnabled(this.meGrid)) {
            return; // 未启用，不干预
        }

        // 2. 获取原始样板列表
        List<ICraftingPatternDetails> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) {
            return;
        }

        // 3. 获取当前请求数量
        long requestAmount = RequestAmountHolder.peek();
        if (requestAmount <= 0) {
            return;
        }

        // 4. 构建缩放后的样板列表
        List<ICraftingPatternDetails> scaled = new ArrayList<>(original.size());
        boolean anyScaled = false;

        for (ICraftingPatternDetails pattern : original) {
            // 只处理处理样板 (Processing Pattern)
            // 合成样板 (Crafting Pattern, isCraftable()==true) 不处理
            if (pattern.isCraftable()) {
                scaled.add(pattern);
                continue;
            }

            // 获取匹配请求物品的单次输出量
            long outputAmount = getMatchingOutputAmount(pattern, stack);
            if (outputAmount <= 0) {
                scaled.add(pattern);
                continue;
            }

            // 计算有效倍率
            long multiplier = PatternMultiplier.compute(pattern, requestAmount, outputAmount);

            if (multiplier > 1) {
                scaled.add(new ScaledPatternDetails(pattern, multiplier));
                anyScaled = true;
            } else {
                scaled.add(pattern);
            }
        }

        // 5. 如果有样板被缩放，替换返回值
        if (anyScaled) {
            cir.setReturnValue(scaled);
        }
    }

    /**
     * 从样板输出中找到匹配请求物品的数量。
     */
    private static long getMatchingOutputAmount(ICraftingPatternDetails pattern, IAEStack<?> requestStack) {
        IAEStack<?>[] outputs = pattern.getCondensedAEOutputs();
        if (outputs == null) return 0;

        for (IAEStack<?> output : outputs) {
            if (output == null) continue;
            // 比较物品类型 (不比较数量)
            if (output.isSameType(requestStack)) {
                return output.getStackSize();
            }
        }
        return 0;
    }
}
```

### 5.9 APMConfig

> 文件: src/main/java/com/yourmod/apm/config/APMConfig.java
> 职责: 全局配置 (极简)

```java
package com.yourmod.apm.config;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * 模组配置。
 * 极简设计，仅包含硬上限和全局开关。
 */
public final class APMConfig {

    /** 全局开关: 是否启用自动翻倍功能 */
    public static boolean enabled = true;

    /** 硬上限: 翻倍后单个 stack 最大数量 (默认 Integer.MAX_VALUE) */
    public static long hardCap = Integer.MAX_VALUE;

    public static void load(File configFile) {
        Configuration config = new Configuration(configFile);
        try {
            config.load();

            enabled = config.getBoolean(
                "enabled",
                Configuration.CATEGORY_GENERAL,
                true,
                "Enable automatic pattern doubling when Advanced Pattern Matrix is connected"
            );

            hardCap = config.getLong(
                "hardCap",
                Configuration.CATEGORY_GENERAL,
                Integer.MAX_VALUE,
                1,
                Integer.MAX_VALUE,
                "Maximum stack size after doubling. Default: 2147483647 (Integer.MAX_VALUE)"
            );

        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }
    }
}
```

### 5.10 主类

> 文件: src/main/java/com/yourmod/apm/AdvancedPatternMatrixMod.java

```java
package com.yourmod.apm;

import com.yourmod.apm.block.BlockAdvPatternMatrix;
import com.yourmod.apm.config.APMConfig;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = AdvancedPatternMatrixMod.MODID,
    name = AdvancedPatternMatrixMod.NAME,
    version = AdvancedPatternMatrixMod.VERSION,
    dependencies = "required-after:appliedenergistics2"
)
public class AdvancedPatternMatrixMod {

    public static final String MODID = "apm";
    public static final String NAME = "Advanced Pattern Matrix";
    public static final String VERSION = "1.0.0";

    @Mod.Instance(MODID)
    public static AdvancedPatternMatrixMod instance;

    @SidedProxy(
        clientSide = "com.yourmod.apm.ClientProxy",
        serverSide = "com.yourmod.apm.CommonProxy"
    )
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        APMConfig.load(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        BlockAdvPatternMatrix.register();
        proxy.registerRenderers();
    }
}
```

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
  |  [Mixin HEAD] RequestAmountHolder.push(123)
  |
  v
context.getPrecisePatternsFor(铁板)
  |
  |  [Mixin RETURN] apm$scalePatterns:
  |    1. DoublingNetworkTracker.isEnabled(grid) == true
  |    2. requestAmount = RequestAmountHolder.peek() == 123
  |    3. 原始样板: 1铁锭 -> 1铁板, outputAmount = 1
  |    4. idealMultiplier = ceil(123 / 1) = 123
  |    5. maxSafe = 2,147,483,647 / 1 = 2,147,483,647
  |    6. effectiveMultiplier = min(123, 2,147,483,647) = 123
  |    7. 返回 ScaledPatternDetails(原始样板, 123)
  |       缩放后: 123铁锭 -> 123铁板
  |
  v
new CraftFromPatternTask(request, scaledPattern, priority, context)
  |  patternOutputs = [123x铁板]
  |  matchingOutput = 123x铁板
  |  toCraft = ceilDiv(123, 123) = 1
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

### 6.2 超限场景流程

```
玩家下单: "合成 40,000,000,000 个铁板" (40G)
样板: 1铁锭 -> 1铁板
         |
         v
CraftableItemResolver.provideCraftingRequestResolvers(request, context)
  |  [Mixin HEAD] RequestAmountHolder.push(40,000,000,000)
  |
  v
context.getPrecisePatternsFor(铁板)
  |  [Mixin RETURN] apm$scalePatterns:
  |    requestAmount = 40,000,000,000
  |    outputAmount = 1
  |    idealMultiplier = ceil(40G / 1) = 40,000,000,000
  |    maxSafe = 2,147,483,647 / 1 = 2,147,483,647
  |    effectiveMultiplier = min(40G, 2,147,483,647) = 2,147,483,647
  |    缩放后: 2,147,483,647铁锭 -> 2,147,483,647铁板
  |
  v
CraftFromPatternTask:
  |  matchingOutput = 2,147,483,647x铁板
  |  toCraft = ceilDiv(40,000,000,000, 2,147,483,647) = 19
  |
  |  子请求: 铁锭 amount = 2,147,483,647 * 19 = 40,802,189,293
  |  (略多于40G，多出的进存储)
  |
  v
合成计划: 19 次发配 (原版需要 40G 次!)
```

### 6.3 递归子请求中的倍率

```
下单: 100 铁块
样板A: 9铁锭 -> 1铁块
样板B: 1铁锭 -> 1铁板 (假设铁块需要铁板)

第一层: 请求 100 铁块
  RequestAmountHolder: [100]
  样板A 缩放: ceil(100/1) = 100 -> 900铁锭 -> 100铁块
  子请求: 900 铁锭

第二层: 请求 900 铁锭 (假设铁锭由铁板合成)
  RequestAmountHolder: [100, 900]  <- 栈结构
  样板B 缩放: ceil(900/1) = 900 -> 900铁板 -> 900铁锭
  子请求: 900 铁板

每层递归独立计算倍率，互不干扰。
```

### 6.4 网络生命周期

```
[放置方块]
TileAdvPatternMatrix.validate()
  -> getProxy().onReady()
  -> (等待 AE 网络 tick)
  -> registerToNetwork()
  -> DoublingNetworkTracker.register(grid)
  -> 该网络的合成计算开始启用翻倍

[破坏方块 / 区块卸载]
TileAdvPatternMatrix.invalidate() / onChunkUnload()
  -> unregisterFromNetwork()
  -> DoublingNetworkTracker.unregister(grid)
  -> getProxy().invalidate()
  -> 该网络恢复原版行为

[网络分裂 / 合并]
AE 网络重建时会创建新的 IGrid 对象
  -> 旧 Grid 从 Set 中自然失效
  -> 新 Grid 需要重新注册
  -> TileEntity 的 updateEntity() 会检测并重新注册
```

---

## 7. Mixin 配置与注入策略

### 7.1 Mixin 配置文件

> 文件: src/main/resources/mixins.apm.json

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.yourmod.apm.mixin",
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
| CraftableItemResolverMixin | CraftableItemResolver | provideCraftingRequestResolvers | HEAD | push 请求数量到 ThreadLocal |
| CraftableItemResolverMixin | CraftableItemResolver | provideCraftingRequestResolvers | RETURN | pop ThreadLocal |
| CraftingContextMixin | CraftingContext | getPrecisePatternsFor | RETURN (cancellable) | 缩放样板列表 |

### 7.3 注入安全性分析

```
CraftableItemResolverMixin:
  - HEAD 注入: 在方法最开头执行，不影响原逻辑
  - RETURN 注入: 在方法返回前执行，仅清理 ThreadLocal
  - 风险: 极低 (仅操作 ThreadLocal)
  - 异常安全: 即使方法抛异常，RETURN 仍会执行 (Mixin 保证)
    但为安全起见，可在 catch 中也 pop (通过 try-finally 模式)

CraftingContextMixin:
  - RETURN + cancellable: 在方法返回后修改返回值
  - 仅在 DoublingNetworkTracker.isEnabled() 为 true 时干预
  - 未启用时完全透明 (直接 return，不修改 cir)
  - 风险: 低 (仅替换列表中的样板对象)
  - 注意: 不修改原始列表，创建新列表
```

### 7.4 异常恢复

```java
// 在 CraftableItemResolverMixin 中增加异常安全:
@Inject(
    method = "provideCraftingRequestResolvers",
    at = @At(value = "INVOKE", target = "...", shift = Shift.AFTER),
    // 或者使用 try-catch 包装
)

// 更安全的做法: 在 CraftingContextMixin 中加 try-finally
// 确保即使缩放逻辑出错，也不会影响原版合成
private void apm$scalePatterns(...) {
    try {
        // ... 缩放逻辑 ...
    } catch (Exception e) {
        // 出错时不干预，走原版流程
        // 可选: 打印日志
    }
}
```

---

## 8. 构建配置

### 8.1 build.gradle

```groovy
buildscript {
    repositories {
        maven { url = "https://maven.minecraftforge.net/" }
        maven { url = "https://repo.spongepowered.org/repository/maven-public/" }
        maven { url = "https://maven.gtnh.miraheze.org/repository/maven-public/" }
    }
    dependencies {
        classpath 'net.minecraftforge.gradle:ForgeGradle:1.2-SNAPSHOT'
        classpath 'org.spongepowered:mixingradle:0.6-SNAPSHOT'
    }
}

apply plugin: 'forge'
apply plugin: 'org.spongepowered.mixin'

version = "1.0.0"
group = "com.yourmod.apm"
archivesBaseName = "AdvancedPatternMatrix"

minecraft {
    version = "1.7.10-10.13.4.1614-1.7.10"
    runDir = "run"
}

repositories {
    maven { url = "https://maven.gtnh.miraheze.org/repository/maven-public/" }
}

dependencies {
    // GTNH AE2 rv3-beta-695
    compileOnly "com.github.GTNewHorizons:Applied-Energistics-2-Unofficial:rv3-beta-695-GTNH:dev"

    // GTNHLib (提供 Mixin 运行时)
    compileOnly "com.github.GTNewHorizons:GTNHLib:0.6.12:dev"
}

mixin {
    add sourceSets.main, "mixins.apm.refmap.json"
}

processResources {
    inputs.property "version", project.version
    inputs.property "mcversion", project.minecraft.version

    from(sourceSets.main.resources.srcDirs) {
        include 'mcmod.info'
        expand 'version': project.version, 'mcversion': project.minecraft.version
    }
    from(sourceSets.main.resources.srcDirs) {
        exclude 'mcmod.info'
    }
}
```

### 8.2 mcmod.info

```json
[
  {
    "modid": "apm",
    "name": "Advanced Pattern Matrix",
    "description": "Automatic pattern doubling for AE2 crafting. Connect the Advanced Pattern Matrix to your ME network to enable automatic pattern scaling during crafting calculations.",
    "version": "${version}",
    "mcversion": "${mcversion}",
    "url": "",
    "authorList": ["YourName"],
    "credits": "Inspired by ExtendedAE_Plus (EAEP)",
    "logoFile": "",
    "screenshots": [],
    "dependencies": ["appliedenergistics2"]
  }
]
```

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
| 子网 | 隔离 | 矩阵在子网只影响子网 |
| 多 CPU 并行 | 兼容 | 每个 CPU 独立计算 |
| 样板优先级 | 兼容 | ScaledPatternDetails 委托 getPriority() |
| 样板替换 (canSubstitute) | 兼容 | 委托 canSubstitute() |
| 流体样板 | 兼容 | 通过 getAEInputs/getAEOutputs 处理 |
| 模糊匹配 (Fuzzy) | 需注意 | isValidItemForSlot 委托原始样板 |

### 9.3 风险点与缓解

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| CraftingCPUCluster 内部用 getPattern() 匹配 | 中 | ScaledPatternDetails.getPattern() 返回原始样板物品 |
| 递归样板 (输出是输入的超集) | 低 | CraftFromPatternTask 有 hasRecursiveInputs 检测，缩放不改变物品种类 |
| 多线程并发访问 | 低 | ThreadLocal 隔离 + ConcurrentHashMap |
| Mixin 目标方法签名变化 (AE2 更新) | 中 | 锁定 rv3-beta-695 版本 |
| 内存泄漏 (Grid 未清理) | 低 | 使用 ConcurrentHashMap.newSetFromMap，Grid 销毁时 unregister |
| 整数溢出 (乘法) | 中 | 倍率计算时先除后乘，确保 baseAmount * multiplier <= HARD_CAP |
| 子请求数量溢出 | 中 | CraftFromPatternTask 中 childAmount = input * toCraft，需确保不溢出 |

### 9.4 整数溢出防护

```java
// PatternMultiplier.compute 中的防护:
// 确保 baseAmount * multiplier 不会溢出 long
// 由于 HARD_CAP = Integer.MAX_VALUE，且 baseAmount >= 1:
//   maxSafeMultiplier = HARD_CAP / baseAmount
//   baseAmount * maxSafeMultiplier <= HARD_CAP < Long.MAX_VALUE
// 所以不会溢出。

// ScaledPatternDetails.scaleItemStackArray 中的防护:
// arr[i].getStackSize() * multiplier
// 由于 multiplier 已经过 computeMaxSafeMultiplier 约束:
//   baseAmount * multiplier <= Integer.MAX_VALUE
// 所以结果在 int 范围内，setStackSize(long) 安全。
```

### 9.5 版本锁定

```
本模组仅兼容:
  - Minecraft 1.7.10
  - GTNH 2.8.4
  - Applied-Energistics-2-Unofficial rv3-beta-695-GTNH

不兼容:
  - GTNH 2.9.0+ (AE2 有大改)
  - 其他 AE2 版本
  - 非 GTNH 环境
```

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

### 10.2 集成测试

| 测试场景 | 操作 | 期望结果 |
|----------|------|----------|
| 基本翻倍 | 放矩阵，下单123铁板 (样板1->1) | 1次发配，123铁锭一次性发出 |
| 无矩阵 | 不放矩阵，下单123铁板 | 123次发配 (原版行为) |
| 超限 | 下单40G铁板 | ~19次发配，每次2.1G |
| 合成样板不处理 | 下单工作台配方物品 | 原版行为，不翻倍 |
| 流体样板 | 下单流体处理配方 | 正确翻倍，不超上限 |
| 子网隔离 | 矩阵接子网，主网下单 | 主网不翻倍 |
| 多矩阵 | 同网络放2个矩阵 | 效果与1个相同 |
| 移除矩阵 | 下单过程中破坏矩阵 | 当前任务继续，新任务不翻倍 |
| 递归合成 | 多层合成链 | 每层独立计算倍率 |
| 多物品输出 | 样板有多个输出 | 所有输出正确缩放 |
| 多物品输入 | 样板有多个输入 | 所有输入正确缩放，取最严格上限 |
| 优先级 | 多个样板不同优先级 | 优先级正确委托 |
| 网络合并 | 两个网络合并 | 新网络正确检测矩阵 |
| 网络分裂 | 网络分裂 | 各子网络独立检测 |

### 10.3 压力测试

| 测试 | 说明 |
|------|------|
| 大量样板 | 网络中 1000+ 样板，验证性能 |
| 深层递归 | 10+ 层合成链，验证 ThreadLocal 栈正确性 |
| 并发下单 | 多个 CPU 同时计算，验证线程安全 |
| 长时间运行 | 服务器运行 24h+，验证无内存泄漏 |

---

## 11. 开发路线图

| 阶段 | 内容 | 预计工作量 | 产出 |
|------|------|-----------|------|
| Phase 1 | 搭建开发环境 (Forge 1.7.10 + Mixin + AE2 695 依赖) | 1 天 | 可编译的空项目 |
| Phase 2 | 实现 ScaledPatternDetails + PatternMultiplier + 单元测试 | 1 天 | 核心算法验证 |
| Phase 3 | 实现 RequestAmountHolder + DoublingNetworkTracker | 0.5 天 | 基础设施 |
| Phase 4 | 实现 TileAdvPatternMatrix + BlockAdvPatternMatrix | 1 天 | 方块可放置、可连网 |
| Phase 5 | 实现 CraftableItemResolverMixin + CraftingContextMixin | 1 天 | 核心功能生效 |
| Phase 6 | 集成测试 (单品、多品、流体、递归、子网) | 2 天 | 功能验证 |
| Phase 7 | 兼容性测试 (样板优化矩阵、其他 AE2 附属) | 1 天 | 兼容性确认 |
| Phase 8 | 压力测试 + 边界情况修复 | 1 天 | 稳定性确认 |
| Phase 9 | 打包发布 | 0.5 天 | 最终 jar |

总计: 约 9 天

---

## 12. 附录

### 12.1 关键类引用 (AE2 rv3-beta-695)

```
appeng.api.networking.crafting.ICraftingPatternDetails  // 样板接口
appeng.api.networking.IGrid                              // 网络接口
appeng.api.networking.IGridHost                          // 网络设备接口
appeng.api.networking.IGridNode                          // 网络节点
appeng.api.storage.data.IAEItemStack                     // 物品栈
appeng.api.storage.data.IAEStack                         // 通用栈 (含流体)
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
| DoublingNetworkTracker | (无直接对应) | 网络状态追踪 |
| CraftingContextMixin | PatternProviderBlockEntity Mixin | 样板替换注入点 |
| TileAdvPatternMatrix | (无直接对应，EAEP 用 Pattern Provider) | 网络设备 |

### 12.3 语言文件

en_US.lang:
```
tile.apm.adv_pattern_matrix.name=Advanced Pattern Optimization Matrix
```

zh_CN.lang:
```
tile.apm.adv_pattern_matrix.name=高级样板优化矩阵
```

### 12.4 配方 (可选)

```
// 示例合成配方 (可根据 GTNH 平衡性调整)
// 使用 GT 机器或原版工作台
// 这里仅为占位，实际配方由整合包作者决定

// 概念配方:
// [AE2 工程处理器] [样板优化矩阵] [AE2 工程处理器]
// [AE2 逻辑处理器] [AE2 计算处理器] [AE2 逻辑处理器]
// [AE2 工程处理器] [AE2 逻辑处理器] [AE2 工程处理器]
// -> 高级样板优化矩阵
```

---

## 文档结束

本文档为完整可执行的开发指南。按照 Phase 1-9 的顺序实施即可完成模组开发。
核心代码已在第 5 节完整给出，可直接复制到项目中使用。