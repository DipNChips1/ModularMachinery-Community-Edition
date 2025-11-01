package github.kasuminova.mmce.common.tile;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.GridAccessException;
import appeng.util.Platform;
import github.kasuminova.mmce.common.tile.base.MEItemBus;
import github.kasuminova.mmce.common.util.MEBusProfiler;
import hellfirepvp.modularmachinery.common.lib.ItemsMM;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import hellfirepvp.modularmachinery.common.util.IOInventory;
import net.minecraft.item.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.locks.ReadWriteLock;

public class MEItemOutputBus extends MEItemBus {

    @Override
    public IOInventory buildInventory() {
        int size = 36;

        int[] slotIDs = new int[size];
        for (int slotID = 0; slotID < slotIDs.length; slotID++) {
            slotIDs[slotID] = slotID;
        }
        IOInventory inv = new IOInventory(this, new int[]{}, slotIDs);
        inv.setStackLimit(Integer.MAX_VALUE, slotIDs);
        inv.setListener(slot -> {
            synchronized (this) {
                changedSlots[slot] = true;
            }
        });
        return inv;
    }

    @Override
    public ItemStack getVisualItemStack() {
        return new ItemStack(ItemsMM.meItemOutputBus);
    }

    // ============================================================
// PROFILING SUPPORT - Report as OUTPUT bus
// ============================================================
    @Override
    protected void reportProfilingData(long tickTimeNanos, long processWorkTimeNanos, long getSlotsTimeNanos) {
        MEBusProfiler.getInstance().recordOutputBusTick(tickTimeNanos, processWorkTimeNanos, getSlotsTimeNanos);
    }

    @Override
    protected void reportEnhancedMetrics(int slotsChecked, int ae2OpsCount, int successfulOps, long itemsTransferred) {
        // This method is not used in Base MMCE implementation
        // Enhanced metrics are reported directly in tickingRequest()
    }
// ============================================================
// END PROFILING SUPPORT
// ============================================================

    @Nullable
    @Override
    public MachineComponent.ItemBus provideComponent() {
        return new MachineComponent.ItemBus(IOType.OUTPUT) {
            @Override
            public IOInventory getContainerProvider() {
                return inventory;
            }
        };
    }

    @Nonnull
    @Override
    public TickingRequest getTickingRequest(@Nonnull final IGridNode node) {
        return new TickingRequest(5, 60, !hasItem(), true);
    }

    @Nonnull
    @Override
    public TickRateModulation tickingRequest(@Nonnull final IGridNode node, final int ticksSinceLastCall) {
        // ============================================================
        // ENHANCED PROFILING: Start timing and metrics
        // ============================================================
        long tickStartTime = MEBusProfiler.isProfilingEnabled() ? System.nanoTime() : 0;
        long[] profilingData = startProfiling();
        long processWorkTime = 0;

        // Enhanced metrics tracking
        int slotsChecked = 0;
        int ae2Operations = 0;
        int successfulOperations = 0;
        long itemsTransferred = 0;
        // ============================================================

        if (!proxy.isActive()) {
            endProfiling(profilingData, tickStartTime);
            if (MEBusProfiler.isProfilingEnabled()) {
                MEBusProfiler.getInstance().recordOutputBusEnhancedMetrics(slotsChecked, ae2Operations, successfulOperations, itemsTransferred);
            }
            return TickRateModulation.IDLE;
        }

        // ============================================================
        // PROFILING: Time getNeedUpdateSlots()
        // ============================================================
        long getSlotsStart = MEBusProfiler.isProfilingEnabled() ? System.nanoTime() : 0;
        int[] needUpdateSlots = getNeedUpdateSlots();
        recordGetSlotsTime(profilingData, getSlotsStart);
        slotsChecked = needUpdateSlots.length;
        // ============================================================

        if (needUpdateSlots.length == 0) {
            endProfiling(profilingData, tickStartTime);
            if (MEBusProfiler.isProfilingEnabled()) {
                MEBusProfiler.getInstance().recordOutputBusEnhancedMetrics(slotsChecked, ae2Operations, successfulOperations, itemsTransferred);
            }
            return TickRateModulation.SLOWER;
        }

        inTick = true;
        boolean successAtLeastOnce = false;

        ReadWriteLock rwLock = inventory.getRWLock();

        try {
            rwLock.writeLock().lock();

            // ============================================================
            // PROFILING: Time work processing
            // ============================================================
            long processWorkStart = MEBusProfiler.isProfilingEnabled() ? System.nanoTime() : 0;
            // ============================================================

            // AE2 Operation #1: Get ME inventory
            IMEMonitor<IAEItemStack> inv = proxy.getStorage().getInventory(channel);
            ae2Operations++;

            for (final int slot : needUpdateSlots) {
                changedSlots[slot] = false;
                ItemStack stack = inventory.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }

                ItemStack extracted = inventory.extractItem(slot, stack.getCount(), false);

                // AE2 Operation #2: Create AE stack
                IAEItemStack aeStack = channel.createStack(extracted);
                ae2Operations++;

                if (aeStack == null) {
                    continue;
                }

                // AE2 Operation #3: Powered insert to ME network
                IAEItemStack left = Platform.poweredInsert(proxy.getEnergy(), inv, aeStack, source);
                ae2Operations++;

                if (left != null) {
                    inventory.setStackInSlot(slot, left.createItemStack());

                    if (aeStack.getStackSize() != left.getStackSize()) {
                        successAtLeastOnce = true;
                        successfulOperations++;
                        itemsTransferred += (aeStack.getStackSize() - left.getStackSize());
                    }
                } else {
                    successAtLeastOnce = true;
                    successfulOperations++;
                    itemsTransferred += aeStack.getStackSize();
                }
            }

            // ============================================================
            // PROFILING: Record work processing time
            // ============================================================
            if (MEBusProfiler.isProfilingEnabled()) {
                processWorkTime = System.nanoTime() - processWorkStart;
                recordProcessWorkTime(profilingData, processWorkTime);
            }
            // ============================================================

            inTick = false;
            rwLock.writeLock().unlock();

            endProfiling(profilingData, tickStartTime);
            if (MEBusProfiler.isProfilingEnabled()) {
                MEBusProfiler.getInstance().recordOutputBusEnhancedMetrics(slotsChecked, ae2Operations, successfulOperations, itemsTransferred);
            }
            return successAtLeastOnce ? TickRateModulation.FASTER : TickRateModulation.SLOWER;
        } catch (GridAccessException e) {
            inTick = false;
            changedSlots = new boolean[changedSlots.length];
            rwLock.writeLock().unlock();

            endProfiling(profilingData, tickStartTime);
            if (MEBusProfiler.isProfilingEnabled()) {
                MEBusProfiler.getInstance().recordOutputBusEnhancedMetrics(slotsChecked, ae2Operations, successfulOperations, itemsTransferred);
            }
            return TickRateModulation.IDLE;
        }
    }

    @Override
    public void markNoUpdate() {
        if (hasChangedSlots()) {
            try {
                proxy.getTick().alertDevice(proxy.getNode());
            } catch (GridAccessException e) {
                // NO-OP
            }
        }

        super.markNoUpdate();
    }
}