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
 *
 * @requiresAppliedEnergistics2Version [rv3-beta-695,)
 */
@Mixin(value = CraftingContext.class, remap = false)
public abstract class CraftingContextMixin {

    @Shadow
    @Final
    public net.minecraft.world.World world;

    @Inject(method = "getPrecisePatternsFor", at = @At("RETURN"), cancellable = true)
    private void apm$scalePatterns(IAEItemStack stack, CallbackInfoReturnable<List<ICraftingPatternDetails>> cir) {
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
