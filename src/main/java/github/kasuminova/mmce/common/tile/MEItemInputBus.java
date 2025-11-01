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
import hellfirepvp.modularmachinery.ModularMachinery;
import hellfirepvp.modularmachinery.common.lib.ItemsMM;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import hellfirepvp.modularmachinery.common.util.IOInventory;
import hellfirepvp.modularmachinery.common.util.ItemUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReadWriteLock;

public class MEItemInputBus extends MEItemBus implements SettingsTransfer {

    private static final String CONFIG_TAG_KEY = "configInventory";

    // A simple cache for AEItemStack.
    private static final Map<ItemStack, IAEItemStack> AE_STACK_CACHE  = new WeakHashMap<>();
    private              IOInventory                  configInventory = buildConfigInventory();

    @Override
    public IOInventory buildInventory() {
        int size = 16;
        int[] slotIDs = new int[size];
        for (int slotID = 0; slotID < size; slotID++) {
            slotIDs[slotID] = slotID;
        }
        IOInventory inv = new IOInventory(this, slotIDs, new int[]{});
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
        return new ItemStack(ItemsMM.meItemInputBus);
    }

    public IOInventory buildConfigInventory() {
        int size = 16;

        int[] slotIDs = new int[size];
        for (int slotID = 0; slotID < size; slotID++) {
            slotIDs[slotID] = slotID;
        }
        IOInventory inv = new IOInventory(this, new int[]{}, new int[]{});
        inv.setStackLimit(Integer.MAX_VALUE, slotIDs);
        inv.setMiscSlots(slotIDs);
        inv.setListener(slot -> {
            synchronized (this) {
                changedSlots[slot] = true;
            }
        });
        return inv;
    }

    // ============================================================
// ENHANCED PROFILING SUPPORT - Report as INPUT bus
// ============================================================
    @Override
    protected void reportProfilingData(long tickTimeNanos, long processWorkTimeNanos, long getSlotsTimeNanos) {
        MEBusProfiler.getInstance().recordInputBusTick(tickTimeNanos, processWorkTimeNanos, getSlotsTimeNanos);
    }

    @Override
    protected void reportEnhancedMetrics(int slotsChecked, int ae2OpsCount, int successfulOps, long itemsTransferred) {
        // This method is not used in Base MMCE implementation
        // Enhanced metrics are reported directly in tickingRequest()
    }
// ============================================================
// END PROFILING SUPPORT
// ============================================================

    @Override
    public void readCustomNBT(final NBTTagCompound compound) {
        super.readCustomNBT(compound);

        if (compound.hasKey(CONFIG_TAG_KEY)) {
            readConfigInventoryNBT(compound.getCompoundTag(CONFIG_TAG_KEY));
        }
    }

    @Override
    public void writeCustomNBT(final NBTTagCompound compound) {
        super.writeCustomNBT(compound);

        compound.setTag(CONFIG_TAG_KEY, configInventory.writeNBT());
    }

    public IOInventory getConfigInventory() {
        return configInventory;
    }

    @Nullable
    @Override
    public MachineComponent.ItemBus provideComponent() {
        return new MachineComponent.ItemBus(IOType.INPUT) {
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

    @Override
    public boolean hasItem() {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
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
                MEBusProfiler.getInstance().recordInputBusEnhancedMetrics(slotsChecked, ae2Operations, successfulOperations, itemsTransferred);
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
                MEBusProfiler.getInstance().recordInputBusEnhancedMetrics(slotsChecked, ae2Operations, successfulOperations, itemsTransferred);
            }
            return TickRateModulation.SLOWER;
        }

        ReadWriteLock rwLock = inventory.getRWLock();

        try {
            rwLock.writeLock().lock();

            boolean successAtLeastOnce = false;
            inTick = true;

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
                ItemStack cfgStack = configInventory.getStackInSlot(slot);
                ItemStack invStack = inventory.getStackInSlot(slot);

                if (cfgStack.isEmpty()) {
                    if (invStack.isEmpty()) {
                        continue;
                    }
                    // Export operation
                    int itemsBefore = invStack.getCount();
                    ItemStack result = insertStackToAE(inv, invStack);
                    ae2Operations += 2; // createStack + poweredInsert

                    inventory.setStackInSlot(slot, result);
                    if (result.getCount() < itemsBefore) {
                        successfulOperations++;
                        itemsTransferred += (itemsBefore - result.getCount());
                    }
                    continue;
                }

                if (!ItemUtils.matchStacks(cfgStack, invStack)) {
                    if (invStack.isEmpty()) {
                        // Import operation
                        ItemStack stack = extractStackFromAE(inv, cfgStack);
                        ae2Operations += 2; // createStack + poweredExtraction

                        inventory.setStackInSlot(slot, stack);
                        if (!stack.isEmpty()) {
                            successAtLeastOnce = true;
                            successfulOperations++;
                            itemsTransferred += stack.getCount();
                        }
                    } else {
                        // Export then import
                        ItemStack exportResult = insertStackToAE(inv, invStack);
                        ae2Operations += 2; // createStack + poweredInsert

                        if (exportResult.isEmpty()) {
                            ItemStack stack = extractStackFromAE(inv, cfgStack);
                            ae2Operations += 2; // createStack + poweredExtraction

                            inventory.setStackInSlot(slot, stack);
                            if (!stack.isEmpty()) {
                                successAtLeastOnce = true;
                                successfulOperations++;
                                itemsTransferred += (invStack.getCount() + stack.getCount());
                            }
                        }
                    }
                    continue;
                }

                if (cfgStack.getCount() == invStack.getCount()) {
                    continue;
                }

                if (cfgStack.getCount() > invStack.getCount()) {
                    // Import more items
                    int countToReceive = cfgStack.getCount() - invStack.getCount();
                    ItemStack stack = extractStackFromAE(inv, ItemUtils.copyStackWithSize(invStack, countToReceive));
                    ae2Operations += 2; // createStack + poweredExtraction

                    if (!stack.isEmpty()) {
                        int newCount = invStack.getCount() + stack.getCount();
                        inventory.setStackInSlot(slot, ItemUtils.copyStackWithSize(invStack, newCount));
                        successAtLeastOnce = true;
                        successfulOperations++;
                        itemsTransferred += stack.getCount();
                        failureCounter[slot] = 0;
                    } else {
                        // If AE doesn't have enough item?
                        failureCounter[slot]++;
                    }
                } else {
                    // Export excess items
                    int countToExtract = invStack.getCount() - cfgStack.getCount();
                    int itemsBefore = countToExtract;
                    ItemStack stack = insertStackToAE(inv, ItemUtils.copyStackWithSize(invStack, countToExtract));
                    ae2Operations += 2; // createStack + poweredInsert

                    if (stack.isEmpty()) {
                        inventory.setStackInSlot(slot, ItemUtils.copyStackWithSize(
                                invStack, invStack.getCount() - countToExtract)
                        );
                        successfulOperations++;
                        itemsTransferred += itemsBefore;
                    } else {
                        inventory.setStackInSlot(slot, ItemUtils.copyStackWithSize(
                                invStack, invStack.getCount() - countToExtract + stack.getCount())
                        );
                        if (stack.getCount() < itemsBefore) {
                            successfulOperations++;
                            itemsTransferred += (itemsBefore - stack.getCount());
                        }
                    }
                    successAtLeastOnce = true;
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
                MEBusProfiler.getInstance().recordInputBusEnhancedMetrics(slotsChecked, ae2Operations, successfulOperations, itemsTransferred);
            }
            return successAtLeastOnce ? TickRateModulation.FASTER : TickRateModulation.SLOWER;
        } catch (GridAccessException e) {
            inTick = false;
            changedSlots = new boolean[changedSlots.length];
            rwLock.writeLock().unlock();

            endProfiling(profilingData, tickStartTime);
            if (MEBusProfiler.isProfilingEnabled()) {
                MEBusProfiler.getInstance().recordInputBusEnhancedMetrics(slotsChecked, ae2Operations, successfulOperations, itemsTransferred);
            }
            return TickRateModulation.IDLE;
        }
    }

    private ItemStack extractStackFromAE(final IMEMonitor<IAEItemStack> inv, final ItemStack stack) throws GridAccessException {
        IAEItemStack aeStack = createStack(stack);
        if (aeStack == null) {
            return ItemStack.EMPTY;
        }

        IAEItemStack extracted = Platform.poweredExtraction(proxy.getEnergy(), inv, aeStack, source);
        if (extracted == null) {
            return ItemStack.EMPTY;
        }
        return extracted.createItemStack();
    }

    private ItemStack insertStackToAE(final IMEMonitor<IAEItemStack> inv, final ItemStack stack) throws GridAccessException {
        IAEItemStack aeStack = createStack(stack);
        if (aeStack == null) {
            return stack;
        }

        IAEItemStack left = Platform.poweredInsert(proxy.getEnergy(), inv, aeStack, source);
        if (left == null) {
            return ItemStack.EMPTY;
        }
        return left.createItemStack();
    }

    private IAEItemStack createStack(final ItemStack stack) {
        return AE_STACK_CACHE.computeIfAbsent(stack, v -> channel.createStack(stack));
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

    public boolean configInvHasItem() {
        for (int i = 0; i < configInventory.getSlots(); i++) {
            ItemStack stack = configInventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void readConfigInventoryNBT(final NBTTagCompound compound) {
        configInventory = IOInventory.deserialize(this, compound);
        configInventory.setListener(slot -> {
            synchronized (this) {
                changedSlots[slot] = true;
            }
        });

        int[] slotIDs = new int[configInventory.getSlots()];
        for (int slotID = 0; slotID < slotIDs.length; slotID++) {
            slotIDs[slotID] = slotID;
        }
        configInventory.setStackLimit(Integer.MAX_VALUE, slotIDs);
    }

    @Override
    public NBTTagCompound downloadSettings() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(CONFIG_TAG_KEY, configInventory.writeNBT());
        return tag;
    }

    @Override
    public void uploadSettings(NBTTagCompound settings) {
        readConfigInventoryNBT(settings.getCompoundTag(CONFIG_TAG_KEY));
        try {
            proxy.getTick().alertDevice(proxy.getNode());
        } catch (GridAccessException e) {
            ModularMachinery.log.warn("Error while uploading settings", e);
        }
    }
}