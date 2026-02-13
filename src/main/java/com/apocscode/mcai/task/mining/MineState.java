package com.apocscode.mcai.task.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent state for a mine operation.
 *
 * Tracks shaft position, hub locations, branch progress, and overall phase.
 * Passed between sub-tasks (DigShaftTask, CreateHubTask, BranchMineTask)
 * so they share context about the mine's layout.
 *
 * Currently in-memory only; NBT persistence planned for Phase D.
 */
public class MineState {

    // ================================================================
    // Mine identity & configuration
    // ================================================================

    /** Ore type being targeted (e.g. "diamond", "iron"). Null = general mining. */
    private String targetOre;

    /** Target Y-level for the primary mining level. */
    private int targetY;

    /** Desired branch tunnel length (blocks). Default 20. */
    private int branchLength = 20;

    /** Number of branch pairs (left+right) per level. Default 4. */
    private int branchesPerSide = 4;

    /** Spacing between branches (center-to-center). Default 4 (3-block gap). */
    private int branchSpacing = 4;

    // ================================================================
    // Shaft & entrance
    // ================================================================

    /** Surface entrance position (where the shaft begins). */
    private BlockPos entrance;

    /** Current shaft bottom position. */
    private BlockPos shaftBottom;

    /** Direction the shaft/hub faces (branches extend perpendicular). */
    private Direction shaftDirection;

    // ================================================================
    // Phase tracking
    // ================================================================

    /** Current overall phase of the mine operation. */
    private MinePhase phase = MinePhase.INITIALIZING;

    /** Phase to resume when unpausing. */
    private MinePhase resumePhase;

    // ================================================================
    // Mining levels
    // ================================================================

    /** All mining levels created in this mine. */
    private final List<MineLevel> levels = new ArrayList<>();

    /** Index of the currently active level in the levels list. */
    private int activeLevelIndex = -1;

    // ================================================================
    // Statistics
    // ================================================================

    /** Total ores mined across all levels. */
    private int totalOresMined = 0;

    /** Total blocks broken (shaft + hub + branches). */
    private int totalBlocksBroken = 0;

    /** Total torches placed. */
    private int totalTorchesPlaced = 0;

    // ================================================================
    // Constructors
    // ================================================================

    public MineState(String targetOre, int targetY, BlockPos entrance, Direction shaftDirection) {
        this.targetOre = targetOre;
        this.targetY = targetY;
        this.entrance = entrance;
        this.shaftDirection = shaftDirection;
    }

    // ================================================================
    // Inner classes
    // ================================================================

    /**
     * A single mining level — hub room + branches at a specific Y-level.
     */
    public static class MineLevel {
        private final int depth;         // Y-level of this floor
        private BlockPos hubCenter;      // Center of the hub room
        private boolean hubBuilt = false;
        private final List<MineBranch> branches = new ArrayList<>();

        /** Positions of placed furniture (chests, furnace, crafting table). */
        private final List<BlockPos> furniturePositions = new ArrayList<>();

        public MineLevel(int depth) {
            this.depth = depth;
        }

        // --- Getters / Setters ---

        public int getDepth() { return depth; }
        public BlockPos getHubCenter() { return hubCenter; }
        public void setHubCenter(BlockPos hubCenter) { this.hubCenter = hubCenter; }
        public boolean isHubBuilt() { return hubBuilt; }
        public void setHubBuilt(boolean hubBuilt) { this.hubBuilt = hubBuilt; }
        public List<MineBranch> getBranches() { return branches; }
        public List<BlockPos> getFurniturePositions() { return furniturePositions; }

        /** Get first incomplete branch, or null if all done. */
        public MineBranch getNextIncompleteBranch() {
            for (MineBranch b : branches) {
                if (b.getStatus() != BranchStatus.COMPLETED) return b;
            }
            return null;
        }

        /** Check if all branches are completed. */
        public boolean isFullyMined() {
            if (branches.isEmpty()) return false;
            return branches.stream().allMatch(b -> b.getStatus() == BranchStatus.COMPLETED);
        }
    }

    /**
     * A single branch tunnel extending from the hub.
     */
    public static class MineBranch {
        private final Direction direction;   // Tunnel direction (perpendicular to shaft)
        private final BlockPos startPos;     // Where branch begins (at hub wall)
        private int currentLength = 0;       // How far we've dug so far
        private int maxLength;               // Target length
        private BranchStatus status = BranchStatus.NOT_STARTED;

        /** Offset along the main corridor (which pair this branch belongs to). */
        private final int corridorOffset;

        public MineBranch(Direction direction, BlockPos startPos, int maxLength, int corridorOffset) {
            this.direction = direction;
            this.startPos = startPos;
            this.maxLength = maxLength;
            this.corridorOffset = corridorOffset;
        }

        // --- Getters / Setters ---

        public Direction getDirection() { return direction; }
        public BlockPos getStartPos() { return startPos; }
        public int getCurrentLength() { return currentLength; }
        public void setCurrentLength(int currentLength) { this.currentLength = currentLength; }
        public int getMaxLength() { return maxLength; }
        public BranchStatus getStatus() { return status; }
        public void setStatus(BranchStatus status) { this.status = status; }
        public int getCorridorOffset() { return corridorOffset; }

        /** Get the position at the end of the dug portion. */
        public BlockPos getCurrentEnd() {
            return startPos.relative(direction, currentLength);
        }
    }

    /**
     * Status of an individual branch tunnel.
     */
    public enum BranchStatus {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        BLOCKED      // Could not continue (lava, bedrock, etc.)
    }

    // ================================================================
    // Phase management
    // ================================================================

    public MinePhase getPhase() { return phase; }

    public void setPhase(MinePhase phase) {
        this.phase = phase;
    }

    public void pause() {
        if (phase.isActive()) {
            resumePhase = phase;
            phase = MinePhase.PAUSED;
        }
    }

    public void resume() {
        if (phase == MinePhase.PAUSED && resumePhase != null) {
            phase = resumePhase;
            resumePhase = null;
        }
    }

    // ================================================================
    // Level management
    // ================================================================

    /** Create a new mining level and add it to the list. */
    public MineLevel addLevel(int depth, BlockPos hubCenter) {
        MineLevel level = new MineLevel(depth);
        level.setHubCenter(hubCenter);
        levels.add(level);
        activeLevelIndex = levels.size() - 1;
        return level;
    }

    /** Get the currently active mining level. */
    public MineLevel getActiveLevel() {
        if (activeLevelIndex >= 0 && activeLevelIndex < levels.size()) {
            return levels.get(activeLevelIndex);
        }
        return null;
    }

    /** Get all mining levels. */
    public List<MineLevel> getLevels() { return levels; }

    // ================================================================
    // Getters / Setters
    // ================================================================

    public String getTargetOre() { return targetOre; }
    public int getTargetY() { return targetY; }
    public BlockPos getEntrance() { return entrance; }
    public BlockPos getShaftBottom() { return shaftBottom; }
    public void setShaftBottom(BlockPos shaftBottom) { this.shaftBottom = shaftBottom; }
    public Direction getShaftDirection() { return shaftDirection; }

    public int getBranchLength() { return branchLength; }
    public void setBranchLength(int branchLength) { this.branchLength = branchLength; }
    public int getBranchesPerSide() { return branchesPerSide; }
    public void setBranchesPerSide(int branchesPerSide) { this.branchesPerSide = branchesPerSide; }
    public int getBranchSpacing() { return branchSpacing; }
    public void setBranchSpacing(int branchSpacing) { this.branchSpacing = branchSpacing; }

    // ================================================================
    // Statistics
    // ================================================================

    public int getTotalOresMined() { return totalOresMined; }
    public void addOresMined(int count) { totalOresMined += count; }
    public int getTotalBlocksBroken() { return totalBlocksBroken; }
    public void addBlocksBroken(int count) { totalBlocksBroken += count; }
    public int getTotalTorchesPlaced() { return totalTorchesPlaced; }
    public void addTorchesPlaced(int count) { totalTorchesPlaced += count; }

    /**
     * Get a human-readable summary of the mine state.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Mine: ");
        if (targetOre != null) sb.append(targetOre).append(" ");
        sb.append("at Y=").append(targetY);
        sb.append(" | Phase: ").append(phase);
        sb.append(" | Levels: ").append(levels.size());
        sb.append(" | Ores mined: ").append(totalOresMined);
        sb.append(" | Blocks broken: ").append(totalBlocksBroken);
        return sb.toString();
    }
}
