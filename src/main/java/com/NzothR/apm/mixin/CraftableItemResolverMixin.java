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
 *
 * @requiresAppliedEnergistics2Version [rv3-beta-695,)
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
