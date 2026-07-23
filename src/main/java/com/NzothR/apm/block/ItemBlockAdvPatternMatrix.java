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
