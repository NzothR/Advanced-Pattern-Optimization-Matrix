package com.NzothR.apm.crafting;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.world.World;

import com.NzothR.apm.AdvancedPatternMatrixMod;

/**
 * 追踪哪些维度启用了自动翻倍（使用维度 ID + 引用计数）。
 */
public final class DoublingNetworkTracker {

    private static final ConcurrentHashMap<Integer, AtomicInteger> dimensionCounts = new ConcurrentHashMap<>();

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
