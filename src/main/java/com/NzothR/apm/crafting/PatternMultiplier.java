package com.NzothR.apm.crafting;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

/**
 * 倍率计算工具。
 */
public final class PatternMultiplier {

    public static final long HARD_CAP = Integer.MAX_VALUE;

    private PatternMultiplier() {}

    public static long compute(ICraftingPatternDetails pattern, long requestAmount, long outputAmount) {
        if (outputAmount <= 0 || requestAmount <= 0) {
            return 1;
        }

        // Fluid patterns have zero-size placeholder stacks. 0 * mul = 0 causes
        // / by zero in AE2's calculateOneStep — skip scaling entirely.
        if (hasZeroSizeStacks(pattern)) {
            return 1;
        }

        long idealMultiplier = ceilDiv(requestAmount, outputAmount);
        long maxSafeMultiplier = computeMaxSafeMultiplier(pattern);
        long effective = Math.min(idealMultiplier, maxSafeMultiplier);

        return Math.max(1, effective);
    }

    /**
     * Returns true if ANY stack (input or output) has size &lt;= 0.
     * Fluid patterns use IAEItemStack placeholders with size=0, and
     * scaling them (0 * N = 0) breaks AE2's perCraftAmount division.
     */
    public static boolean hasZeroSizeStacks(ICraftingPatternDetails pattern) {
        IAEItemStack[] inputs = pattern.getCondensedInputs();
        if (inputs != null) {
            for (IAEItemStack stack : inputs) {
                if (stack != null && stack.getStackSize() <= 0) return true;
            }
        }
        IAEItemStack[] outputs = pattern.getCondensedOutputs();
        if (outputs != null) {
            for (IAEItemStack stack : outputs) {
                if (stack != null && stack.getStackSize() <= 0) return true;
            }
        }
        return false;
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
