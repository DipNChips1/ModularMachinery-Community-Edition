package github.kasuminova.mmce.common.tile;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.GridAccessException;
import appeng.util.Platform;
import github.kasuminova.mmce.common.tile.base.MEItemBus;
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

public class MEItemInputBus extends MEItemBus implements SettingsTransfer {
    private static final String CONFIG_TAG_KEY = "configInventory";
    private IOInventory configInventory;

    public MEItemInputBus() {
        super();
        this.configInventory = buildConfigInventory();
        this.configInventory.setListener(this::onConfigSlotChanged);
    }

    @Override
    public IOInventory buildInventory() {
        int size = 16;
        int[] slotIDs = new int[size];
        for (int i = 0; i < size; i++) slotIDs[i] = i;
        IOInventory inv = new IOInventory(this, slotIDs, new int[]{});
        inv.setStackLimit(Integer.MAX_VALUE, slotIDs);
        return inv;
    }

    @Override
    public ItemStack getVisualItemStack() {
        return new ItemStack(ItemsMM.meItemInputBus);
    }

    public IOInventory buildConfigInventory() {
        int size = 16;
        int[] slotIDs = new int[size];
        for (int i = 0; i < size; i++) slotIDs[i] = i;
        IOInventory inv = new IOInventory(this, new int[]{}, new int[]{});
        inv.setStackLimit(Integer.MAX_VALUE, slotIDs);
        inv.setMiscSlots(slotIDs);
        return inv;
    }

    // ----------------------------
    // Config & Inventory Changes
    // ----------------------------

    /**
     * ✅ FIXED: Now uses wakeDeviceSafe() to wake when config changes
     */
    protected void onConfigSlotChanged(int slot) {
        updateWorkPlanForSlot(slot);

        // ✅ NEW: Wake device if work was added
        if (hasWorkToDo()) {
            wakeDeviceSafe();
        }
    }

    /**
     * ✅ FIXED: Now uses wakeDeviceSafe() from base class
     * Note: Base class already handles this, but we override to be explicit
     */
    @Override
    protected void onInventorySlotChanged(int slot) {
        updateWorkPlanForSlot(slot);

        // ✅ NEW: Wake device if work was added
        if (hasWorkToDo()) {
            wakeDeviceSafe();
        }
    }

    @Override
    protected void updateWorkPlanForSlot(int slot) {
        if (slot < 0 || slot >= requireWork.length) return;

        // 🔒 CRITICAL: Prevent NPE during super() constructor call
        if (this.configInventory == null) {
            requireWork[slot] = null;
            return;
        }

        ItemStack cfgStack = configInventory.getStackInSlot(slot);
        ItemStack invStack = inventory.getStackInSlot(slot);
        if (cfgStack.isEmpty()) {
            // Config empty: export any existing items
            if (!invStack.isEmpty()) {
                requireWork[slot] = channel.createStack(invStack);
                if (requireWork[slot] != null) {
                    requireWork[slot] = requireWork[slot].copy();
                    requireWork[slot].setStackSize(-requireWork[slot].getStackSize());
                }
            } else {
                requireWork[slot] = null;
            }
            return;
        }
        if (invStack.isEmpty()) {
            // Import full config stack
            requireWork[slot] = channel.createStack(cfgStack);
            if (requireWork[slot] != null) {
                requireWork[slot] = requireWork[slot].copy();
                requireWork[slot].setStackSize(cfgStack.getCount());
            }
            return;
        }
        if (!ItemUtils.matchStacks(cfgStack, invStack)) {
            // Mismatch: export current
            requireWork[slot] = channel.createStack(invStack);
            if (requireWork[slot] != null) {
                requireWork[slot] = requireWork[slot].copy();
                requireWork[slot].setStackSize(-requireWork[slot].getStackSize());
            }
            return;
        }
        // Same item, adjust count
        int diff = cfgStack.getCount() - invStack.getCount();
        if (diff == 0) {
            requireWork[slot] = null;
        } else if (diff > 0) {
            // Import missing
            requireWork[slot] = channel.createStack(cfgStack);
            if (requireWork[slot] != null) {
                requireWork[slot] = requireWork[slot].copy();
                requireWork[slot].setStackSize(diff);
            }
        } else {
            // Export excess
            requireWork[slot] = channel.createStack(invStack);
            if (requireWork[slot] != null) {
                requireWork[slot] = requireWork[slot].copy();
                requireWork[slot].setStackSize(diff); // negative
            }
        }
    }

    // ----------------------------
    // Work Processing (AE2-style)
    // ----------------------------

    @Override
    protected boolean processWork(int[] slots, IMEMonitor<IAEItemStack> aeInv) throws GridAccessException {
        boolean didWork = false;
        for (int slot : slots) {
            IAEItemStack work = requireWork[slot];
            if (work == null) continue;
            boolean success = usePlan(slot, work, aeInv);
            if (success) {
                didWork = true;
                updateWorkPlanForSlot(slot); // Re-evaluate after success
            }
            // On failure, requireWork[slot] remains set for retry
        }
        return didWork;
    }

    private boolean usePlan(int slot, IAEItemStack work, IMEMonitor<IAEItemStack> aeInv) throws GridAccessException {
        long amount = work.getStackSize();
        ItemStack invStack = inventory.getStackInSlot(slot);
        if (amount < 0) {
            // Export
            long toExport = -amount;
            ItemStack exportStack = ItemUtils.copyStackWithSize(invStack, (int) Math.min(toExport, invStack.getCount()));
            if (exportStack.isEmpty()) return false;
            IAEItemStack aeExport = channel.createStack(exportStack);
            IAEItemStack leftover = Platform.poweredInsert(proxy.getEnergy(), aeInv, aeExport, source);
            long inserted = aeExport.getStackSize() - (leftover != null ? leftover.getStackSize() : 0);
            if (inserted > 0) {
                ItemStack newStack = ItemUtils.copyStackWithSize(invStack, invStack.getCount() - (int) inserted);
                inventory.setStackInSlot(slot, newStack);
                return true;
            }
        } else {
            // Import
            IAEItemStack toRequest = work.copy();
            IAEItemStack extracted = Platform.poweredExtraction(proxy.getEnergy(), aeInv, toRequest, source);
            if (extracted != null && extracted.getStackSize() > 0) {
                ItemStack newStack;
                if (invStack.isEmpty()) {
                    newStack = extracted.createItemStack();
                } else {
                    newStack = ItemUtils.copyStackWithSize(invStack, invStack.getCount() + (int) extracted.getStackSize());
                }
                inventory.setStackInSlot(slot, newStack);
                return true;
            }
        }
        return false;
    }

    // ----------------------------
    // NBT & Settings
    // ----------------------------

    @Override
    public void readCustomNBT(final NBTTagCompound compound) {
        super.readCustomNBT(compound);
        if (compound.hasKey(CONFIG_TAG_KEY)) {
            readConfigInventoryNBT(compound.getCompoundTag(CONFIG_TAG_KEY));
        }

        // ✅ FIX: Rebuild work plan after BOTH inventory and config are loaded
        // This is critical because super.readCustomNBT() builds the work plan before
        // configInventory is loaded, resulting in all requireWork[i] = null
        for (int i = 0; i < requireWork.length; i++) {
            updateWorkPlanForSlot(i);
        }

        // ✅ NEW: Wake device if work exists after loading
        if (hasWorkToDo()) {
            wakeDeviceSafe();
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

    public void readConfigInventoryNBT(final NBTTagCompound compound) {
        configInventory = IOInventory.deserialize(this, compound);
        configInventory.setListener(this::onConfigSlotChanged);
        int[] slotIDs = new int[configInventory.getSlots()];
        for (int i = 0; i < slotIDs.length; i++) slotIDs[i] = i;
        configInventory.setStackLimit(Integer.MAX_VALUE, slotIDs);
        // Rebuild work plan
        for (int i = 0; i < requireWork.length; i++) {
            updateWorkPlanForSlot(i);
        }

        // ✅ NEW: Wake device if work exists after config load
        if (hasWorkToDo()) {
            wakeDeviceSafe();
        }
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
    }

    public boolean configInvHasItem() {
        for (int i = 0; i < configInventory.getSlots(); i++) {
            if (!configInventory.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}