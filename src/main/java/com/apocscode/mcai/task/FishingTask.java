package com.apocscode.mcai.task;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/**
 * Task: Companion pathfinds to water and fishes.
 * Simulates fishing with random catch times and loot.
 * Produces fish, treasure, and junk items.
 */
public class FishingTask extends CompanionTask {

    private enum Phase { FIND_WATER, NAVIGATING, FISHING }

    private Phase phase;
    private BlockPos waterPos;
    private int stuckTimer = 0;
    private int fishTimer = 0;
    private int caughtCount = 0;
    private final int targetCatch;

    // Average ~8 seconds per catch (160 ticks), with variance
    private static final int MIN_CATCH_TIME = 100; // 5 sec
    private static final int MAX_CATCH_TIME = 300; // 15 sec

    public FishingTask(CompanionEntity companion, int targetCatch) {
        super(companion, "Fish (target: " + targetCatch + ")");
        this.targetCatch = targetCatch;
    }

    @Override
    public int getProgressPercent() {
        return targetCatch > 0 ? (caughtCount * 100) / targetCatch : -1;
    }

    @Override
    protected void start() {
        phase = Phase.FIND_WATER;
        waterPos = findNearbyWater();

        if (waterPos == null) {
            fail("No water source found within 20 blocks.");
            return;
        }

        phase = Phase.NAVIGATING;
        say("Found water! Heading there to fish.");
        navigateTo(waterPos);
    }

    @Override
    protected void tick() {
        switch (phase) {
            case NAVIGATING -> tickNavigating();
            case FISHING -> tickFishing();
            default -> {}
        }
    }

    private void tickNavigating() {
        if (isInReach(waterPos, 3.0)) {
            phase = Phase.FISHING;
            fishTimer = getRandomCatchTime();
            companion.getLookControl().setLookAt(
                    waterPos.getX() + 0.5, waterPos.getY() + 0.5, waterPos.getZ() + 0.5);
            say("Casting line...");
            return;
        }

        stuckTimer++;
        if (stuckTimer % 20 == 0) navigateTo(waterPos);
        if (stuckTimer > 200) {
            fail("Couldn't reach the water.");
        }
    }

    private void tickFishing() {
        // Look at water
        companion.getLookControl().setLookAt(
                waterPos.getX() + 0.5, waterPos.getY() + 0.5, waterPos.getZ() + 0.5);

        fishTimer--;
        if (fishTimer <= 0) {
            // Caught something!
            ItemStack caught = generateCatch();
            companion.getCompanionInventory().addItem(caught);
            companion.playSound(SoundEvents.FISHING_BOBBER_SPLASH, 1.0F, 1.0F);
            caughtCount++;

            if (caughtCount >= targetCatch) {
                say("Done fishing! Caught " + caughtCount + " items.");
                complete();
                return;
            }

            // Reset timer for next catch
            fishTimer = getRandomCatchTime();

            // Periodic update
            if (caughtCount % 3 == 0) {
                say("Caught " + caughtCount + "/" + targetCatch + " items so far...");
            }
        }
    }

    private ItemStack generateCatch() {
        float roll = companion.getRandom().nextFloat();

        if (roll < 0.60f) {
            // 60% fish
            return switch (companion.getRandom().nextInt(4)) {
                case 0 -> new ItemStack(Items.COD);
                case 1 -> new ItemStack(Items.SALMON);
                case 2 -> new ItemStack(Items.TROPICAL_FISH);
                default -> new ItemStack(Items.PUFFERFISH);
            };
        } else if (roll < 0.85f) {
            // 25% junk
            return switch (companion.getRandom().nextInt(5)) {
                case 0 -> new ItemStack(Items.STRING);
                case 1 -> new ItemStack(Items.LEATHER);
                case 2 -> new ItemStack(Items.BONE);
                case 3 -> new ItemStack(Items.INK_SAC);
                default -> new ItemStack(Items.LILY_PAD);
            };
        } else {
            // 15% treasure
            return switch (companion.getRandom().nextInt(4)) {
                case 0 -> new ItemStack(Items.NAME_TAG);
                case 1 -> new ItemStack(Items.NAUTILUS_SHELL);
                case 2 -> new ItemStack(Items.SADDLE);
                default -> new ItemStack(Items.BOW);
            };
        }
    }

    private int getRandomCatchTime() {
        return MIN_CATCH_TIME + companion.getRandom().nextInt(MAX_CATCH_TIME - MIN_CATCH_TIME);
    }

    private BlockPos findNearbyWater() {
        BlockPos center = companion.blockPosition();
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int x = -20; x <= 20; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -20; z <= 20; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (companion.level().getFluidState(pos).is(Fluids.WATER)) {
                        // Ensure we can stand next to it
                        BlockPos standPos = findStandingSpot(pos);
                        if (standPos != null) {
                            double dist = companion.distanceToSqr(
                                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                            if (dist < closestDist) {
                                closestDist = dist;
                                closest = standPos;
                            }
                        }
                    }
                }
            }
        }
        return closest;
    }

    private BlockPos findStandingSpot(BlockPos waterPos) {
        BlockPos[] neighbors = { waterPos.north(), waterPos.south(), waterPos.east(), waterPos.west() };
        for (BlockPos n : neighbors) {
            if (companion.level().getBlockState(n).isAir()
                    && companion.level().getBlockState(n.below()).isSolidRender(companion.level(), n.below())) {
                return n;
            }
        }
        return null;
    }

    @Override
    protected void cleanup() {}
}
