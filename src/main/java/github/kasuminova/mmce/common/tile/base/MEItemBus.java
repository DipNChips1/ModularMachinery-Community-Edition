package github.kasuminova.mmce.common.tile.base;

import appeng.api.AEApi;
import appeng.me.GridAccessException;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import hellfirepvp.modularmachinery.common.util.IOInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Base class for ME Item Buses, modeled after AE2's DualityInterface.
 * Uses a requireWork[] plan, sleeps when idle, and batches slot processing.
 */
public abstract class MEItemBus extends MEMachineComponent implements IGridTickable {
    protected final IItemStorageChannel channel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    protected IOInventory inventory;
    // AE2-style work plan: null = no work, non-null = work needed
    protected IAEItemStack[] requireWork;
    // Cached ME inventory for the current tick
    protected transient IMEMonitor<IAEItemStack> cachedAeInv = null;
    protected boolean cacheValid = false;

    // ✅ NEW: Track if we're currently inside tickingRequest to prevent alertDevice corruption
    protected boolean inTick = false;
    // ✅ NEW: Flag to wake device after current tick completes
    protected boolean needsWakeAfterTick = false;
    // ✅ NEW: Round-robin slot index to prevent slot starvation
    protected int nextSlotToProcess = 0;

    public MEItemBus() {
        this.inventory = buildInventory();
        this.requireWork = new IAEItemStack[inventory.getSlots()];

        // Register listener to detect external inventory changes (e.g., machine consumption)
        this.inventory.setListener(this::onInventorySlotChanged);

        // Initialize work plan using subclass logic – safe even if inventory is empty
        for (int i = 0; i < requireWork.length; i++) {
            updateWorkPlanForSlot(i);
        }
    }

    public abstract IOInventory buildInventory();

    // ----------------------------
    // Work Plan & Ticking
    // ----------------------------

    protected boolean hasWorkToDo() {
        // Null-safe check for partially initialized state
        if (requireWork == null) return false;

        for (IAEItemStack work : requireWork) {
            if (work != null) return true;
        }
        return false;
    }

    /**
     * Returns up to maxSlots slots that need work, using round-robin to prevent slot starvation.
     * ✅ FIXED: Now uses round-robin scheduling instead of always starting from slot 0
     */
    protected int[] getSlotsNeedingWork(int maxSlots) {
        if (requireWork == null || requireWork.length == 0) {
            return new int[0];
        }

        int[] result = new int[Math.min(maxSlots, requireWork.length)];
        int count = 0;

        // Start from nextSlotToProcess and wrap around
        for (int offset = 0; offset < requireWork.length && count < maxSlots; offset++) {
            int i = (nextSlotToProcess + offset) % requireWork.length;
            if (requireWork[i] != null) {
                result[count++] = i;
            }
        }

        // Update nextSlotToProcess for next tick (wrap around)
        if (count > 0) {
            // Start after the last processed slot for next time
            nextSlotToProcess = (result[count - 1] + 1) % requireWork.length;
        }

        if (count == 0) return new int[0];
        if (count == result.length) return result;

        // Shrink array
        int[] trimmed = new int[count];
        System.arraycopy(result, 0, trimmed, 0, count);
        return trimmed;
    }

    @Override
    public TickingRequest getTickingRequest(@Nonnull IGridNode node) {
        // Start awake with canBeAlerted=true for responsiveness
        // The device will naturally sleep when hasWorkToDo() returns false
        return new TickingRequest(5, 40, false, true);
    }

    @Nonnull
    @Override
    public final TickRateModulation tickingRequest(@Nonnull IGridNode node, int ticksSinceLastCall) {
        // ✅ CRITICAL: Mark that we're inside a tick to prevent alertDevice during tick
        inTick = true;
        needsWakeAfterTick = false;

        try {
            // Verify proxy is valid before doing anything
            if (proxy == null || !proxy.isActive()) {
                return TickRateModulation.SLEEP;
            }

            // Refresh ME inventory cache if needed
            if (!cacheValid) {
                cachedAeInv = proxy.getStorage().getInventory(channel);
                cacheValid = true;
            }

            int[] slotsToProcess = getSlotsNeedingWork(8); // Batch up to 8 slots
            if (slotsToProcess.length == 0) {
                return TickRateModulation.SLEEP;
            }

            boolean didWork = processWork(slotsToProcess, cachedAeInv);
            return hasWorkToDo() ?
                    (didWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER) :
                    TickRateModulation.SLEEP;
        } catch (GridAccessException e) {
            // Lost grid access – invalidate cache and sleep
            cachedAeInv = null;
            cacheValid = false;
            return TickRateModulation.SLEEP;
        } catch (Exception e) {
            // Catch ANY exception to prevent crashes
            cachedAeInv = null;
            cacheValid = false;
            return TickRateModulation.SLEEP;
        } finally {
            // ✅ CRITICAL: Mark that we've exited the tick
            inTick = false;

            // ✅ NEW: If work was added during tick, wake device now that it's safe
            if (needsWakeAfterTick && hasWorkToDo()) {
                wakeDeviceSafe();
            }
        }
    }

    /**
     * Subclasses implement their transfer logic here.
     * They MUST update requireWork[slot] = null on success,
     * or leave it set to retry later.
     */
    protected abstract void updateWorkPlanForSlot(int slot);

    protected abstract boolean processWork(int[] slots, IMEMonitor<IAEItemStack> aeInv) throws GridAccessException;

    // ----------------------------
    // Safe Waking Mechanism
    // ----------------------------

    /**
     * ✅ NEW: Safely wakes the device without corrupting AE2's PriorityQueue.
     * This method checks if we're inside a tick and defers waking if necessary.
     */
    protected void wakeDeviceSafe() {
        if (inTick) {
            // We're inside tickingRequest - defer waking until tick completes
            needsWakeAfterTick = true;
            return;
        }

        // Safe to wake now - not inside a tick
        try {
            if (proxy != null && proxy.isActive()) {
                proxy.getTick().wakeDevice(proxy.getNode());
            }
        } catch (GridAccessException e) {
            // Grid not accessible, ignore
        }
    }

    // ----------------------------
    // Inventory Change Handling
    // ----------------------------

    /**
     * Called when inventory changes externally (e.g., machine consumes items).
     * Delegates to subclass via updateWorkPlanForSlot (polymorphic).
     *
     * ✅ FIXED: Now uses wakeDeviceSafe() to wake the device without corrupting PriorityQueue
     */
    protected void onInventorySlotChanged(int slot) {
        updateWorkPlanForSlot(slot);

        // ✅ NEW: Wake device if work was added and we're not in a tick
        if (hasWorkToDo()) {
            wakeDeviceSafe();
        }
    }

    // ----------------------------
    // Grid & Cache Management
    // ----------------------------

    @Override
    public void gridChanged() {
        // Invalidate cache when grid changes
        cachedAeInv = null;
        cacheValid = false;

        // Rebuild work plan now that grid is active
        for (int i = 0; i < requireWork.length; i++) {
            updateWorkPlanForSlot(i);
        }

        // ✅ NEW: Wake device if work exists after grid change
        if (hasWorkToDo()) {
            wakeDeviceSafe();
        }
    }

    // ----------------------------
    // Capability & NBT
    // ----------------------------

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(inventory);
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public void readCustomNBT(NBTTagCompound compound) {
        super.readCustomNBT(compound);
        if (compound.hasKey("inventory")) {
            readInventoryNBT(compound.getCompoundTag("inventory"));
        }
        // ✅ NEW: Restore round-robin position
        if (compound.hasKey("nextSlotToProcess")) {
            nextSlotToProcess = compound.getInteger("nextSlotToProcess");
        }
    }

    public void readInventoryNBT(NBTTagCompound tag) {
        this.inventory = IOInventory.deserialize(this, tag);
        this.requireWork = new IAEItemStack[inventory.getSlots()];

        // Re-set listener after deserialization
        this.inventory.setListener(this::onInventorySlotChanged);

        // Re-initialize using subclass logic
        for (int i = 0; i < requireWork.length; i++) {
            updateWorkPlanForSlot(i);
        }

        // Reapply stack limits
        int[] slotIDs = new int[inventory.getSlots()];
        for (int i = 0; i < slotIDs.length; i++) slotIDs[i] = i;
        inventory.setStackLimit(Integer.MAX_VALUE, slotIDs);

        // ✅ NEW: Wake device if work exists after loading from NBT
        // Note: This is safe because readCustomNBT is never called during ticking
        if (hasWorkToDo()) {
            wakeDeviceSafe();
        }
    }

    @Override
    public void writeCustomNBT(NBTTagCompound compound) {
        super.writeCustomNBT(compound);
        compound.setTag("inventory", inventory.writeNBT());
        // ✅ NEW: Save round-robin position
        compound.setInteger("nextSlotToProcess", nextSlotToProcess);
    }

    public boolean hasItem() {
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public IOInventory getInternalInventory() {
        return inventory;
    }
}