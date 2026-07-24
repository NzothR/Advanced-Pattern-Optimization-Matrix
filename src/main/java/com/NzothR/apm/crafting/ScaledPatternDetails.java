package com.NzothR.apm.crafting;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

/**
 * Scaled pattern wrapper. Does NOT cache scaled arrays — AE2's crafting engine
 * mutates returned stacks in-place (e.g. setting size to 0 during subtraction),
 * so caching would return stale/zeroed data and cause {@code / by zero} in
 * {@code CraftFromPatternTask.calculateOneStep}.
 */
public class ScaledPatternDetails implements ICraftingPatternDetails {

    private static final IAEItemStack[] EMPTY = new IAEItemStack[0];

    private final ICraftingPatternDetails original;
    private final long multiplier;

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
        return scale(original.getInputs());
    }

    @Override
    public IAEItemStack[] getCondensedInputs() {
        return scale(original.getCondensedInputs());
    }

    @Override
    public IAEItemStack[] getOutputs() {
        return scale(original.getOutputs());
    }

    @Override
    public IAEItemStack[] getCondensedOutputs() {
        return scale(original.getCondensedOutputs());
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
