package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Task: Gather specific blocks nearby (flowers, mushrooms, sand, gravel, etc.)
 * Scans for the target block type and collects them.
 */
public class GatherBlocksTask extends CompanionTask {

    private static final int[] EXPAND_RADII = {32, 48}; // fallback search radii
    private static final int DIG_DOWN_MAX = 8; // max blocks to dig down to reach stone
    private final Block[] targetBlocks;
    private int radius;
    private final int maxBlocks;
    private final Deque<BlockPos> targets = new ArrayDeque<>();
    private BlockPos currentTarget;
    private int stuckTimer = 0;
    private int blocksGathered = 0;
    private int totalBlocks = 0;
    private boolean diggingDown = false;
    private BlockPos digTarget = null;

    public GatherBlocksTask(CompanionEntity companion, Block targetBlock, int radius, int maxBlocks) {
        this(companion, new Block[]{targetBlock}, radius, maxBlocks);
    }

    public GatherBlocksTask(CompanionEntity companion, Block[] targetBlocks, int radius, int maxBlocks) {
        super(companion);
        this.targetBlocks = targetBlocks;
        this.radius = radius;
        this.maxBlocks = maxBlocks > 0 ? maxBlocks : 999;
    }

    @Override
    public String getTaskName() {
        return "Gather " + targetBlocks[0].getName().getString() + " (r=" + radius + ")";
    }

    @Override
    public int getProgressPercent() {
        return totalBlocks > 0 ? (blocksGathered * 100) / totalBlocks : -1;
    }

    @Override
    protected void start() {
        List<BlockPos> found = BlockHelper.scanForBlocks(companion, targetBlocks, radius, maxBlocks);
        targets.addAll(found);
        if (targets.isEmpty()) {
            // Expand search radius progressively before giving up
            for (int expandRadius : EXPAND_RADII) {
                if (expandRadius <= radius) continue;
                MCAi.LOGGER.info("GatherBlocksTask: no {} at r={}, expanding to r={}",
                        targetBlocks[0].getName().getString(), radius, expandRadius);
                radius = expandRadius;
                found = BlockHelper.scanForBlocks(companion, targetBlocks, radius, maxBlocks);
                targets.addAll(found);
                if (!targets.isEmpty()) break;
            }
        }
        if (targets.isEmpty()) {
            // For stone-type blocks, try digging down from current position
            if (isStoneType()) {
                // Don't dig down inside the home area
                if (companion.isInHomeArea(companion.blockPosition())) {
                    MCAi.LOGGER.info("GatherBlocksTask: inside home area, won't dig down");
                    fail("No " + targetBlocks[0].getName().getString() + " found outside home area");
                    return;
                }
                MCAi.LOGGER.info("GatherBlocksTask: no surface {}, will try digging down",
                        targetBlocks[0].getName().getString());
                diggingDown = true;
                digTarget = companion.blockPosition().below();
                say("No exposed " + targetBlocks[0].getName().getString() + " nearby — digging down to find some!");
                return;
            }
            MCAi.LOGGER.warn("GatherBlocksTask: no {} blocks found within r={}",
                    targetBlocks[0].getName().getString(), radius);
            say("Couldn't find any " + targetBlocks[0].getName().getString() + " nearby.");
            fail("No " + targetBlocks[0].getName().getString() + " found within radius " + radius);
            return;
        }
        totalBlocks = targets.size();
        say("Found " + totalBlocks + " " + targetBlocks[0].getName().getString() + " to gather!");
    }

    @Override
    protected void tick() {
        // === Dig-down mode: break soft blocks until we hit stone ===
        if (diggingDown) {
            tickDigDown();
            return;
        }

        if (blocksGathered >= maxBlocks || targets.isEmpty()) {
            MCAi.LOGGER.info("GatherBlocksTask: finished — gathered {}/{} {} blocks",
                    blocksGathered, maxBlocks, targetBlocks[0].getName().getString());
            if (blocksGathered == 0) {
                fail("Found " + targetBlocks[0].getName().getString() + " blocks but couldn't reach any (0 gathered)");
            } else {
                complete();
            }
            return;
        }

        if (currentTarget == null) {
            currentTarget = targets.peek();
            stuckTimer = 0;
        }

        // Check if block is still a valid target (not already mined or changed)
        Block currentBlock = companion.level().getBlockState(currentTarget).getBlock();
        boolean isTarget = false;
        for (Block t : targetBlocks) {
            if (currentBlock == t) { isTarget = true; break; }
        }

        if (companion.level().getBlockState(currentTarget).isAir() || !isTarget) {
            targets.poll();
            currentTarget = null;
            return;
        }

        if (isInReach(currentTarget, 3.0)) {
            companion.equipBestToolForBlock(companion.level().getBlockState(currentTarget));
            BlockHelper.breakBlock(companion, currentTarget);
            targets.poll();
            currentTarget = null;
            stuckTimer = 0;
            blocksGathered++;
        } else {
            navigateTo(currentTarget);
            stuckTimer++;
            if (stuckTimer > 120) {
                targets.poll();
                currentTarget = null;
                stuckTimer = 0;
            }
        }
    }

    /**
     * Dig down through soft blocks (dirt, grass, gravel, sand) to reach stone-type blocks.
     * Used when no stone/cobblestone is found on the surface.
     * Once stone is found, switches to normal gather mode.
     */
    private void tickDigDown() {
        if (digTarget == null) {
            fail("Dig target lost");
            return;
        }

        // Check how far we've dug
        int depth = companion.blockPosition().getY() - digTarget.getY();
        if (depth > DIG_DOWN_MAX) {
            MCAi.LOGGER.warn("GatherBlocksTask: dug down {} blocks without finding target",
                    DIG_DOWN_MAX);
            fail("Dug down " + DIG_DOWN_MAX + " blocks but couldn't find "
                    + targetBlocks[0].getName().getString());
            return;
        }

        Level level = companion.level();
        BlockState belowState = level.getBlockState(digTarget);
        Block below = belowState.getBlock();

        // Check if we hit a target block
        for (Block t : targetBlocks) {
            if (below == t) {
                MCAi.LOGGER.info("GatherBlocksTask: found {} at depth {}, switching to gather mode",
                        t.getName().getString(), depth + 1);
                diggingDown = false;
                // Now do a fresh scan — we've exposed stone
                List<BlockPos> found = BlockHelper.scanForBlocks(companion, targetBlocks, 4, maxBlocks);
                if (!found.isEmpty()) {
                    targets.addAll(found);
                    totalBlocks = targets.size();
                    say("Found " + totalBlocks + " " + targetBlocks[0].getName().getString() + " underground!");
                } else {
                    // At minimum, the block below us is a target
                    targets.add(digTarget);
                    totalBlocks = 1;
                }
                return;
            }
        }

        // Not a target block yet — break it if it's soft enough
        if (belowState.isAir()) {
            // Air — just fall or move down
            digTarget = digTarget.below();
            return;
        }

        // Break soft blocks (dirt, grass, gravel, sand, etc.)
        // Stone-type blocks that aren't our targets get broken too if we have a tool
        boolean isSoft = below == Blocks.DIRT || below == Blocks.GRASS_BLOCK
                || below == Blocks.COARSE_DIRT || below == Blocks.PODZOL
                || below == Blocks.SAND || below == Blocks.RED_SAND
                || below == Blocks.GRAVEL || below == Blocks.CLAY
                || below == Blocks.ROOTED_DIRT || below == Blocks.MUD
                || below == Blocks.MUDDY_MANGROVE_ROOTS
                || below == Blocks.SOUL_SAND || below == Blocks.SOUL_SOIL
                || belowState.is(BlockTags.DIRT);

        if (isSoft || isInReach(digTarget, 3.0)) {
            companion.equipBestToolForBlock(belowState);
            BlockHelper.breakBlock(companion, digTarget);
            digTarget = digTarget.below();
        } else {
            // Can't reach or break — try navigating closer
            navigateTo(digTarget);
            stuckTimer++;
            if (stuckTimer > 60) {
                MCAi.LOGGER.warn("GatherBlocksTask: stuck while digging down at {}",
                        digTarget);
                fail("Got stuck while digging down to find "
                        + targetBlocks[0].getName().getString());
            }
        }
    }

    /**
     * Check if the target blocks are stone-type (underground blocks).
     * These blocks might require digging down to reach from the surface.
     */
    private boolean isStoneType() {
        for (Block b : targetBlocks) {
            if (b == Blocks.STONE || b == Blocks.COBBLESTONE
                    || b == Blocks.DEEPSLATE || b == Blocks.COBBLED_DEEPSLATE
                    || b == Blocks.CALCITE || b == Blocks.TUFF
                    || b == Blocks.DRIPSTONE_BLOCK || b == Blocks.BASALT
                    || b == Blocks.BLACKSTONE || b == Blocks.NETHERRACK) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void cleanup() {
        targets.clear();
    }
}
