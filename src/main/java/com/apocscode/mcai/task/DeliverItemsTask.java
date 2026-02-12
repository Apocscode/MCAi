package com.apocscode.mcai.task;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Task: Deliver items from companion inventory to a specific position.
 * Companion pathfinds to the destination and drops/deposits items.
 * If a container is at the target, deposits into it; otherwise drops on ground.
 */
public class DeliverItemsTask extends CompanionTask {

    private enum Phase { NAVIGATING, DELIVERING }

    private final BlockPos destination;
    private final Item itemToDeliver;  // null = deliver all
    private final int count;           // -1 = deliver all matching
    private Phase phase;
    private int stuckTimer = 0;
    private int delivered = 0;

    public DeliverItemsTask(CompanionEntity companion, BlockPos destination,
                             Item itemToDeliver, int count, String locationName) {
        super(companion, "Deliver " +
                (itemToDeliver != null ? count + "x " + itemToDeliver.getDescription().getString() : "items") +
                " to " + locationName);
        this.destination = destination;
        this.itemToDeliver = itemToDeliver;
        this.count = count;
    }

    @Override
    public int getProgressPercent() {
        if (phase == Phase.DELIVERING) return 90;
        return -1;
    }

    @Override
    protected void start() {
        phase = Phase.NAVIGATING;
        say("Heading to deliver items!");
        navigateTo(destination);
    }

    @Override
    protected void tick() {
        switch (phase) {
            case NAVIGATING -> tickNavigating();
            case DELIVERING -> tickDelivering();
        }
    }

    private void tickNavigating() {
        if (isInReach(destination, 3.0)) {
            phase = Phase.DELIVERING;
            return;
        }

        stuckTimer++;
        if (stuckTimer % 20 == 0) navigateTo(destination);
        if (stuckTimer > 200) {
            fail("Couldn't reach the delivery destination — path blocked.");
        }
    }

    private void tickDelivering() {
        SimpleContainer inv = companion.getCompanionInventory();
        BlockEntity be = companion.level().getBlockEntity(destination);

        int remaining = count == -1 ? Integer.MAX_VALUE : count - delivered;

        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (itemToDeliver != null && stack.getItem() != itemToDeliver) continue;

            int toMove = Math.min(stack.getCount(), remaining);
            ItemStack toDeliver = stack.copyWithCount(toMove);

            if (be instanceof BaseContainerBlockEntity container) {
                // Deposit into container
                for (int s = 0; s < container.getContainerSize(); s++) {
                    ItemStack slot = container.getItem(s);
                    if (slot.isEmpty()) {
                        int fit = Math.min(toDeliver.getCount(), toDeliver.getMaxStackSize());
                        container.setItem(s, toDeliver.copyWithCount(fit));
                        toDeliver.shrink(fit);
                        break;
                    } else if (ItemStack.isSameItemSameComponents(slot, toDeliver)) {
                        int space = slot.getMaxStackSize() - slot.getCount();
                        int fit = Math.min(toDeliver.getCount(), space);
                        if (fit > 0) {
                            slot.grow(fit);
                            toDeliver.shrink(fit);
                            break;
                        }
                    }
                }
                int moved = toMove - toDeliver.getCount();
                stack.shrink(moved);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                delivered += moved;
                remaining -= moved;
                container.setChanged();
            } else {
                // Drop on ground at destination
                companion.level().addFreshEntity(
                        new net.minecraft.world.entity.item.ItemEntity(
                                companion.level(),
                                destination.getX() + 0.5, destination.getY() + 1, destination.getZ() + 0.5,
                                toDeliver));
                stack.shrink(toMove);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                delivered += toMove;
                remaining -= toMove;
            }
        }

        say("Delivered " + delivered + " items.");
        complete();
    }

    @Override
    protected void cleanup() {}
}
