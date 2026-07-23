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
                    xCoord,
                    yCoord,
                    zCoord,
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
                xCoord,
                yCoord,
                zCoord,
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
