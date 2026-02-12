package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionChat;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.TaggedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.EnumSet;
import java.util.List;

/**
 * Logistics Goal — the companion autonomously moves items between tagged containers.
 *
 * Behavior cycle (AUTO mode only):
 *   1. Walk to an INPUT container
 *   2. Pull items from it into companion inventory
 *   3. Walk to the appropriate OUTPUT or STORAGE container
 *   4. Push items from inventory into the container
 *   5. Repeat
 *
 * Runs at low priority so tasks, combat, eating, etc. take precedence.
 * Cooldown between cycles to avoid hyperactive behavior.
 */
public class CompanionLogisticsGoal extends Goal {
    private final CompanionEntity companion;

    private enum Phase { IDLE, GOING_TO_INPUT, PULLING, GOING_TO_OUTPUT, PUSHING }
    private Phase phase = Phase.IDLE;

    private BlockPos sourcePos;
    private BlockPos destPos;
    private int cooldown;
    private int pathRetryTimer;
    private int stuckTimer;

    private static final double INTERACT_REACH = 2.5;
    private static final int CYCLE_COOLDOWN = 60;       // 3 sec between cycles
    private static final int MAX_ITEMS_PER_TRIP = 16;    // Items per transfer
    private static final int STUCK_TIMEOUT = 100;        // 5 sec stuck = skip

    public CompanionLogisticsGoal(CompanionEntity companion) {
        this.companion = companion;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (companion.getBehaviorMode() != CompanionEntity.BehaviorMode.AUTO) return false;
        if (companion.level().isClientSide) return false;
        if (companion.getTaskManager().hasTasks()) return false; // Tasks take priority
        if (cooldown > 0) { cooldown--; return false; }

        List<TaggedBlock> inputs = companion.getTaggedBlocks(TaggedBlock.Role.INPUT);
        if (inputs.isEmpty()) return false;

        // Check if any INPUT container has items to move
        Level level = companion.level();
        for (TaggedBlock input : inputs) {
            IItemHandler handler = level.getCapability(
                    Capabilities.ItemHandler.BLOCK, input.pos(), null);
            if (handler != null && hasItems(handler)) {
                sourcePos = input.pos();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (companion.getBehaviorMode() != CompanionEntity.BehaviorMode.AUTO) return false;
        return phase != Phase.IDLE;
    }

    @Override
    public void start() {
        phase = Phase.GOING_TO_INPUT;
        pathRetryTimer = 0;
        stuckTimer = 0;
        navigateToBlock(sourcePos);
    }

    @Override
    public void tick() {
        switch (phase) {
            case GOING_TO_INPUT -> tickGoingToInput();
            case PULLING -> tickPulling();
            case GOING_TO_OUTPUT -> tickGoingToOutput();
            case PUSHING -> tickPushing();
            default -> {}
        }
    }

    @Override
    public void stop() {
        phase = Phase.IDLE;
        sourcePos = null;
        destPos = null;
        companion.getNavigation().stop();
        cooldown = CYCLE_COOLDOWN;
    }

    // ================================================================
    // Phase implementations
    // ================================================================

    private void tickGoingToInput() {
        if (sourcePos == null) { resetToIdle(); return; }

        lookAt(sourcePos);
        if (isInReach(sourcePos)) {
            phase = Phase.PULLING;
            stuckTimer = 0;
            return;
        }

        stuckTimer++;
        if (stuckTimer > STUCK_TIMEOUT) {
            companion.getChat().say(CompanionChat.Category.STUCK,
                    "Can't reach the input container. Skipping this cycle.");
            resetToIdle();
            return;
        }

        pathRetryTimer++;
        if (pathRetryTimer % 20 == 0) {
            navigateToBlock(sourcePos);
        }
    }

    private void tickPulling() {
        Level level = companion.level();
        IItemHandler source = level.getCapability(
                Capabilities.ItemHandler.BLOCK, sourcePos, null);
        if (source == null) { resetToIdle(); return; }

        SimpleContainer inv = companion.getCompanionInventory();
        int pulled = 0;

        for (int i = 0; i < source.getSlots() && pulled < MAX_ITEMS_PER_TRIP; i++) {
            ItemStack available = source.extractItem(i, MAX_ITEMS_PER_TRIP - pulled, true);
            if (available.isEmpty()) continue;

            // Try to fit in companion inventory
            ItemStack toInsert = available.copy();
            int beforeCount = toInsert.getCount();

            for (int j = 0; j < inv.getContainerSize(); j++) {
                ItemStack existing = inv.getItem(j);
                if (existing.isEmpty()) {
                    inv.setItem(j, toInsert.copy());
                    toInsert.setCount(0);
                    break;
                } else if (ItemStack.isSameItemSameComponents(existing, toInsert)
                        && existing.getCount() < existing.getMaxStackSize()) {
                    int space = existing.getMaxStackSize() - existing.getCount();
                    int transfer = Math.min(space, toInsert.getCount());
                    existing.grow(transfer);
                    toInsert.shrink(transfer);
                    if (toInsert.isEmpty()) break;
                }
            }

            int actualPulled = beforeCount - toInsert.getCount();
            if (actualPulled > 0) {
                source.extractItem(i, actualPulled, false); // Actually extract
                pulled += actualPulled;
            }
        }

        if (pulled > 0) {
            level.playSound(null, sourcePos, SoundEvents.ITEM_PICKUP,
                    SoundSource.PLAYERS, 0.5F, 1.0F);

            // Find destination
            destPos = findBestDestination();
            if (destPos != null) {
                phase = Phase.GOING_TO_OUTPUT;
                pathRetryTimer = 0;
                stuckTimer = 0;
                navigateToBlock(destPos);
            } else {
                companion.getChat().say(CompanionChat.Category.TASK,
                        "No OUTPUT or STORAGE container available to deliver items.");
                resetToIdle();
            }
        } else {
            // Nothing to pull — done
            resetToIdle();
        }
    }

    private void tickGoingToOutput() {
        if (destPos == null) { resetToIdle(); return; }

        lookAt(destPos);
        if (isInReach(destPos)) {
            phase = Phase.PUSHING;
            stuckTimer = 0;
            return;
        }

        stuckTimer++;
        if (stuckTimer > STUCK_TIMEOUT) {
            companion.getChat().say(CompanionChat.Category.STUCK,
                    "Can't reach the output container. Skipping delivery.");
            resetToIdle();
            return;
        }

        pathRetryTimer++;
        if (pathRetryTimer % 20 == 0) {
            navigateToBlock(destPos);
        }
    }

    private void tickPushing() {
        Level level = companion.level();
        IItemHandler dest = level.getCapability(
                Capabilities.ItemHandler.BLOCK, destPos, null);
        if (dest == null) { resetToIdle(); return; }

        SimpleContainer inv = companion.getCompanionInventory();
        int pushed = 0;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            // Try to insert into destination
            ItemStack remaining = stack.copy();
            for (int j = 0; j < dest.getSlots(); j++) {
                remaining = dest.insertItem(j, remaining, false);
                if (remaining.isEmpty()) break;
            }

            int inserted = stack.getCount() - remaining.getCount();
            if (inserted > 0) {
                stack.shrink(inserted);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                pushed += inserted;
            }
        }

        if (pushed > 0) {
            level.playSound(null, destPos, SoundEvents.ITEM_PICKUP,
                    SoundSource.PLAYERS, 0.5F, 0.8F);
        }

        // Cycle complete
        resetToIdle();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private void resetToIdle() {
        phase = Phase.IDLE;
        sourcePos = null;
        destPos = null;
    }

    private BlockPos findBestDestination() {
        // Prefer OUTPUT containers, fall back to STORAGE
        List<TaggedBlock> outputs = companion.getTaggedBlocks(TaggedBlock.Role.OUTPUT);
        for (TaggedBlock output : outputs) {
            IItemHandler handler = companion.level().getCapability(
                    Capabilities.ItemHandler.BLOCK, output.pos(), null);
            if (handler != null && hasSpace(handler)) {
                return output.pos();
            }
        }

        List<TaggedBlock> storage = companion.getTaggedBlocks(TaggedBlock.Role.STORAGE);
        for (TaggedBlock s : storage) {
            IItemHandler handler = companion.level().getCapability(
                    Capabilities.ItemHandler.BLOCK, s.pos(), null);
            if (handler != null && hasSpace(handler)) {
                return s.pos();
            }
        }
        return null;
    }

    private boolean hasItems(IItemHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) return true;
        }
        return false;
    }

    private boolean hasSpace(IItemHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }

    private boolean isInReach(BlockPos pos) {
        double dist = companion.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return dist < INTERACT_REACH * INTERACT_REACH;
    }

    private void navigateToBlock(BlockPos pos) {
        companion.getNavigation().moveTo(
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
    }

    private void lookAt(BlockPos pos) {
        companion.getLookControl().setLookAt(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
