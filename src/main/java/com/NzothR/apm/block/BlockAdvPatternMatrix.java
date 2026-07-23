package com.NzothR.apm.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.registry.GameRegistry;

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
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        return false;
    }

    public static void register() {
        GameRegistry.registerBlock(INSTANCE, ItemBlockAdvPatternMatrix.class, "adv_pattern_matrix");
        GameRegistry.registerTileEntity(TileAdvPatternMatrix.class, "apm:adv_pattern_matrix");
    }
}
