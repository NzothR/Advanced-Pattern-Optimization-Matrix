package com.NzothR.apm.block;

import net.minecraft.entity.player.EntityPlayer;
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
    private boolean nodeReady = false;

    /**
     * Has the proxy's owner been set? Set by onPlacedBy() for first placement, or
     * by readFromNBT() for chunk-reload. Guards against calling onReady() before
     * the node has an identity — required for AE2 security terminal compatibility.
     */
    private boolean ownerReady = false;

    // ==================== Public hooks ====================

    /** Called from BlockAdvPatternMatrix.onBlockPlacedBy — the correct init path for first placement. */
    public void onPlacedBy(EntityPlayer player) {
        if (!worldObj.isRemote) {
            getProxy().setOwner(player);
            ownerReady = true;
            initNode();
        }
    }

    // ==================== TileEntity lifecycle ====================

    @Override
    public void validate() {
        super.validate();
        if (!worldObj.isRemote) {
            if (!nodeReady && ownerReady) {
                // Chunk-reload: readFromNBT already restored owner from NBT. Safe to onReady.
                initNode();
            }
            // First placement: onPlacedBy hasn't fired yet. Do NOT call onReady() —
            // the node would have no owner and the security terminal would reject it.
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (!worldObj.isRemote) {
            unregisterFromNetwork();
            getProxy().invalidate();
            nodeReady = false;
            ownerReady = false;
        }
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (!worldObj.isRemote) {
            unregisterFromNetwork();
            getProxy().onChunkUnload();
            nodeReady = false;
        }
    }

    // ==================== Common init ====================

    private void initNode() {
        if (!nodeReady) {
            getProxy().onReady();
            nodeReady = true;
        }
        registerToNetwork();
    }

    // ==================== IGridProxyable ====================

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

    /**
     * Called by AE2 security system when this block's node is rejected from the network.
     * Instead of destroying the block, disconnect the node gracefully. The block stays
     * in the world; it will auto-retry on chunk reload.
     */
    @Override
    public void securityBreak() {
        AdvancedPatternMatrixMod.LOG.warn(
            "[APM] Security break at ({}, {}, {}), dim={} — disconnecting node",
            xCoord,
            yCoord,
            zCoord,
            worldObj != null ? worldObj.provider.dimensionId : -1);
        unregisterFromNetwork();
        if (getProxy().isReady()) {
            getProxy().invalidate();
        }
        nodeReady = false;
        ownerReady = false;
        registered = false;
    }

    // ==================== Network registration ====================

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

    // ==================== Tick ====================

    @Override
    public void updateEntity() {
        if (!worldObj.isRemote) {
            if (!nodeReady) {
                if (retryCooldown > 0) {
                    retryCooldown--;
                    return;
                }
                retryCooldown = RETRY_COOLDOWN_TICKS;
                // Edge-case fallback: world-gen placed or other non-standard placement.
                // Call onReady() without owner — if there's no security terminal,
                // this works fine; if there is one, securityBreak will handle it.
                getProxy().onReady();
                nodeReady = true;
            }
            if (!registered) {
                registerToNetwork();
            }
        }
    }

    // ==================== NBT ====================

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        getProxy().readFromNBT(data);
        registered = false;
        nodeReady = false;
        // Proxy owner was restored by readFromNBT above — mark ready for validate().
        ownerReady = true;
    }

    @Override
    public void writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        getProxy().writeToNBT(data);
    }
}
