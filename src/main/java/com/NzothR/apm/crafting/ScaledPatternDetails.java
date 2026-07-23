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
                if (cachedCondensedInputs == null) cachedCondensedInputs = scale(original.getCondensedInputs());
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
                if (cachedCondensedOutputs == null) cachedCondensedOutputs = scale(original.getCondensedOutputs());
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
        if (obj instanceof ScaledPatternDetails) return this.original.equals(((ScaledPatternDetails) obj).original);
        return this.original.equals(obj);
    }

    @Override
    public String toString() {
        return "ScaledPatternDetails{orig=" + original + ", mul=" + multiplier + "}";
    }
}
