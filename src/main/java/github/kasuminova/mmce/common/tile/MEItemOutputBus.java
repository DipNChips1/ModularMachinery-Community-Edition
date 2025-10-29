package github.kasuminova.mmce.common.tile;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.GridAccessException;
import appeng.util.Platform;
import github.kasuminova.mmce.common.tile.base.MEItemBus;
import hellfirepvp.modularmachinery.common.lib.ItemsMM;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import hellfirepvp.modularmachinery.common.util.IOInventory;
import hellfirepvp.modularmachinery.common.util.ItemUtils;
import net.minecraft.item.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class MEItemOutputBus extends MEItemBus {
    public MEItemOutputBus() {
        super();
    }

    @Override
    public IOInventory buildInventory() {
        int size = 36;
        int[] slotIDs = new int[size];
        for (int i = 0; i < size; i++) slotIDs[i] = i;
        IOInventory inv = new IOInventory(this, new int[]{}, slotIDs);
        inv.setStackLimit(Integer.MAX_VALUE, slotIDs);
        return inv;
    }

    @Override
    public ItemStack getVisualItemStack() {
        return new ItemStack(ItemsMM.meItemOutputBus);
    }

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

    @Override
    protected void updateWorkPlanForSlot(int slot) {
        if (slot < 0 || slot >= requireWork.length) return;
        ItemStack inv = inventory.getStackInSlot(slot);
        requireWork[slot] = inv.isEmpty() ? null :
                channel.createStack(inv).setStackSize(-inv.getCount()); // .copy() not needed in AE2 rv6
    }

    @Override
    protected boolean processWork(int[] slots, IMEMonitor<IAEItemStack> aeInv) throws GridAccessException {
        boolean didWork = false;
        for (int slot : slots) {
            IAEItemStack work = requireWork[slot];
            if (work == null) continue;
            boolean success = usePlan(slot, work, aeInv);
            if (success) {
                didWork = true;
                updateWorkPlanForSlot(slot);
            }
        }
        return didWork;
    }

    private boolean usePlan(int slot, IAEItemStack work, IMEMonitor<IAEItemStack> aeInv) throws GridAccessException {
        if (work.getStackSize() >= 0) return false;
        ItemStack current = inventory.getStackInSlot(slot);
        if (current.isEmpty()) return false;
        long toExport = Math.min(-work.getStackSize(), current.getCount());
        ItemStack exportStack = ItemUtils.copyStackWithSize(current, (int) toExport);
        IAEItemStack aeExport = channel.createStack(exportStack);
        IAEItemStack leftover = Platform.poweredInsert(proxy.getEnergy(), aeInv, aeExport, source);
        long inserted = aeExport.getStackSize() - (leftover != null ? leftover.getStackSize() : 0);
        if (inserted > 0) {
            inventory.setStackInSlot(slot, ItemUtils.copyStackWithSize(current, current.getCount() - (int) inserted));
            return true;
        }
        return false;
    }

    @Override
    public boolean hasItem() {
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}