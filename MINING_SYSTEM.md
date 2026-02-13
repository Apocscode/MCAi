# MCAi Mining System — Architecture & Design

## Overview

A comprehensive mining system that allows the AI companion to create and operate long-term mines. Inspired by MineColonies' node-based shaft mining and Minecraft Wiki's branch mining best practices, adapted for a single AI companion.

When the player says "create a mine" or "build a mine", the companion will systematically:
1. Dig a main shaft down to target depth
2. Create a staging hub with storage
3. Execute branch mining with optimal spacing
4. Place torches to prevent mob spawning
5. Manage inventory (deposit into staging chests when full)
6. Persist mine state for resumption across sessions

---

## Ore Distribution Reference (1.21.1)

| Ore | Min Y | Max Y | Best Y | Pick Tier | Notes |
|-----|-------|-------|--------|-----------|-------|
| Coal | 0 | 320 | 96 | Wood | Common everywhere |
| Copper | -16 | 112 | 48 | Wood | More in dripstone caves |
| Iron | -64 | 320 | 16 | Stone | Also common at Y=232 |
| Lapis | -64 | 64 | 0 | Stone | |
| Gold | -64 | 32 | -16 | Iron | Also badlands Y=32-256 |
| Redstone | -64 | 16 | -59 | Iron | |
| Diamond | -64 | 16 | -59 | Iron | Best at -58/-59 |
| Emerald | -16 | 320 | 232 | Iron | Mountains only |

**Key Mining Levels:**
- **Y=16**: Best for Iron (also gets some gold, lapis, diamonds)
- **Y=-59**: Best for Diamond + Redstone (deepslate layer, slower mining)
- **Y=48**: Best for Copper
- **Y=-54**: Good multi-ore level (diamond + redstone + iron + gold + lapis, above lava lakes at -55)

---

## Architecture

### New Files

```
src/main/java/com/apocscode/mcai/
├── task/
│   ├── mining/
│   │   ├── MineState.java          — Persistent mine data (NBT serializable)
│   │   ├── CreateMineTask.java      — Orchestrator: phases of mine creation
│   │   ├── DigShaftTask.java        — Dig staircase shaft to target Y
│   │   ├── CreateHubTask.java       — Clear room + place staging furniture
│   │   ├── BranchMineTask.java      — Systematic branch mining from hub
│   │   └── MinePhase.java           — Phase enum for mine creation
│   └── (existing tasks unchanged)
├── ai/tool/
│   ├── CreateMineTool.java          — AI tool: "create_mine" command
│   ├── ResumeMiningTool.java        — AI tool: "resume_mining" command  
│   └── (existing tools unchanged)
└── util/
    └── PlacementHelper.java         — Block/item placement (torches, chests, ladders)
```

### MineState — Persistent Mine Data

```java
public class MineState {
    // Identity
    UUID ownerUUID;
    String mineName;             // "iron_mine", "diamond_mine", etc.
    
    // Shaft
    BlockPos entrance;           // Surface entrance position
    BlockPos shaftBottom;        // Current bottom of shaft
    Direction shaftDirection;    // Direction staircase faces
    int targetY;                 // Target depth
    
    // Levels (each level = a hub + branches)
    List<MineLevel> levels;      // All mining levels
    int activeLevelIndex;        // Which level is currently being mined
    
    // State
    MinePhase currentPhase;      // SHAFT_DIG, HUB_CREATE, BRANCH_MINE, etc.
    boolean isActive;
    long totalOresMined;
    long totalBlocksMined;
    
    // Serialize to/from NBT for persistence
    CompoundTag save();
    void load(CompoundTag tag);
}

public class MineLevel {
    int depth;                   // Y coordinate of this level
    BlockPos hubCenter;          // Center of the staging room
    List<MineBranch> branches;   // All branches on this level
    boolean hubBuilt;
    
    // Hub inventory tracking
    BlockPos chestPos;
    BlockPos furnacePos;
    BlockPos craftingTablePos;
}

public class MineBranch {
    Direction direction;         // N/S/E/W from hub
    int currentLength;           // How far we've dug
    int maxLength;               // Target length (32-64 blocks)
    int oresMined;
    BranchStatus status;         // PENDING, IN_PROGRESS, COMPLETED
}
```

### MinePhase — State Machine

```
INITIALIZING
    │
    ▼
DIGGING_SHAFT ──────────────► (shaft reaches target Y)
    │                             │
    │ (pause/resume)              ▼
    │                        CREATING_HUB ──────► (room cleared, furniture placed)
    │                             │
    │                             ▼
    │                        BRANCH_MINING ──────► (all branches at length)
    │                             │
    │                             │ (inventory full)
    │                             ▼
    │                        DEPOSITING ──────────► (back to BRANCH_MINING)
    │                             │
    │                             │ (level exhausted)
    │                             ▼
    │                        DEEPENING ───────────► (dig shaft deeper)
    │                             │
    │                             ▼
    │                        CREATING_HUB (next level)
    │
    ▼
COMPLETED / PAUSED
```

---

## Detailed Phase Design

### Phase 1: Shaft Digging (DigShaftTask)

**Design: 2×1 Staircase (Safest for AI)**
- Dig a 2-block-high, 1-block-wide staircase going down at 45°
- Move 1 block forward + 1 block down per step
- Place torches every 8 blocks on the left wall (mob spawn prevention)
- Place cobblestone floor under each step (to prevent falls)
- Safety: Check for lava/water before breaking each block
- If lava encountered: place cobblestone to seal, shift tunnel 1 block sideways
- If gravel/sand falls: auto-break falling blocks

```
(Side view - staircase shaft)
Surface
  █  T             ← Torch
  ██               
   ██  T           
    ██             
     ██  T         
      ██           
       ██  T       
        ██         
         ██  T     ← Target Y level
          ██████████ ← Hub starts here
```

**Why staircase over vertical shaft?**
- MineColonies uses vertical shaft + ladders, but ladder placement is complex for AI
- Staircase is walkable — companion can walk up/down without ladders
- Safer: can't fall to death, easier lava avoidance
- Can see ahead while descending

**Alternative: Spiral Staircase (3×3)**
- Better for deep mines — stays in fewer chunks
- More blocks exposed per Y-level
- Harder to implement (turn logic)
- Consider for Phase 2 improvements

### Phase 2: Hub Creation (CreateHubTask)

**Hub Layout (7×5×4 room):**
```
(Top-down view at target Y)
███████████
█         █
█  C   F  █
█    T    █  ← T=Torch, C=Chest, F=Furnace
█  X   W  █  ← X=Crafting Table, W=Chest #2
█         █
█  ←shaft █  ← Shaft entrance
███████████

(Side view)
█████████████
█  T     T  █  ← Ceiling torches
█           █
█  C  X  F  █  ← Furniture on floor
█████████████  ← Floor
```

**Placement Requirements:**
- Clear 7×5×4 room (length × width × height)
- Floor: solid blocks (cobblestone if needed)
- Ceiling: solid blocks (cobble if needed — prevent gravel cave-ins)
- Place 4 torches (corners or walls) 
- Place 1-2 chests (staging storage)
- Place 1 crafting table
- Place 1 furnace (for smelting ores on-site)
- Entrance: 2-high opening facing shaft

**PlacementHelper Needed:**
```java
public class PlacementHelper {
    static boolean placeBlock(companion, pos, itemStack);
    static boolean placeTorch(companion, pos, wallFace);
    static boolean placeChest(companion, pos);
    static boolean placeFurnace(companion, pos);
    static boolean placeCraftingTable(companion, pos);
    static boolean hasItemInInventory(companion, Item);
    static ItemStack findInInventory(companion, Item);
}
```

### Phase 3: Branch Mining (BranchMineTask)

**Layout (optimized from Minecraft Wiki research):**

Branch spacing of **3 blocks** between tunnels (good balance of efficiency + thoroughness ~90%+ ore discovery):

```
(Top-down view of branching from hub)

                    N
                    │
     ═══════════════╪═══════════════  ← Branch N (32 blocks)
                    │
     ═══════════╗   │
     ═══════════╣   │   ← 3-block spacing
     ═══════════╝   │
                    │
W ══════════════════╬══════════════════ E
                    │
                    │   ╔═══════════
                    │   ╠═══════════  ← 3-block spacing  
                    │   ╚═══════════
                    │
     ═══════════════╪═══════════════  ← Branch S
                    │

═ = 1×2 tunnel (player height)
╬ = Hub center
```

**Branch Mining Algorithm:**
1. Start from hub, face direction (N/S/E/W)
2. Dig 2-high × 1-wide tunnel forward
3. Every block forward: scan ±2 blocks for ore exposure
4. If ore found: side-trip to mine it (like current StripMineTask)
5. Place torch every 8 blocks (left wall)
6. Every 4th block: dig 1×1 "poke holes" left and right (exposes more blocks cheaply)
7. After main branch: step 3 blocks sideways, dig parallel branch
8. Repeat for each cardinal direction from hub

**Poke-hole Mining (from Minecraft Wiki Layout 6):**
```
(Top-down — dramatically increases blocks revealed per block mined)

  P   P   P   P   P     ← Poke holes (1×1×1 left)
══════════════════════   ← 1×2 main branch
  P   P   P   P   P     ← Poke holes (1×1×1 right)
```
Every 4th block, dig 1 block left and 1 block right. This reveals 4 extra blocks per poke for only 2 blocks mined. On a 32-block branch, that's 16 poke holes = 32 extra blocks mined but 64 extra blocks REVEALED.

### Phase 4: Inventory Management

**When companion inventory gets 80% full:**
1. Pause current branch mining
2. Navigate back to hub chest
3. Deposit all non-essential items (keep: pickaxes, torches, food, cobblestone)
4. Resume mining from where stopped

**On-site smelting (optional, advanced):**
- If furnace placed and coal available
- Smelt raw ores into ingots (saves inventory space: raw iron → iron ingot)
- Can be done during deposit trips

### Phase 5: Torch Placement Logic

**Torch Spacing Rules (from Minecraft Wiki):**
- Mob spawn prevention requires light level 0 → torches every 13 blocks max
- Conservative: every 8 blocks (accounts for corners and side tunnels)
- Place on LEFT wall (navigation convention — return by keeping torches on RIGHT)

**PlacementHelper.placeTorch:**
```java
// Try wall placement first (preferred), fall back to ground
boolean placeTorch(CompanionEntity companion, BlockPos pos, Direction wallFace) {
    // Check if wall block is solid (can hold torch)
    // Check if torch position is air
    // Check companion has torches in inventory
    // Place wall torch or ground torch
}
```

**Torch crafting if depleted:**
- Companion should carry coal + sticks
- If out of torches but has materials → craft more at hub crafting table
- System prompt should instruct AI to ensure torch supply before mining

### Phase 6: Hazard Handling Improvements

**Current:** Only checks lava adjacency via `isSafeToMine()`

**Proposed Enhancements:**

| Hazard | Detection | Response |
|--------|-----------|----------|
| Lava adjacent | Check 6 faces before break | Skip block, log warning |
| Lava pool ahead | Check 2 blocks ahead in tunnel | Place cobblestone wall, shift |
| Water flow | Check for water in tunnel ahead | Place block to redirect |
| Gravel/sand fall | Detect gravity blocks above | Mine them as they fall |
| Cave breach | Air detected in tunnel walls | Place wall blocks to seal |
| Mob sounds | N/A (can't detect) | Torch placement prevents |
| Inventory full | Check 80% capacity | Deposit run to hub |
| Tool breaking | Durability < 10% | Return to hub, craft new |
| Suffocation | Existing system | Auto-break blocks around head |
| Void/bedrock | Y < -60 | Stop digging, mark level done |

**Falling Block Handler:**
```java
// When breaking a block, check if block above is affected by gravity
if (level.getBlockState(pos.above()).getBlock() instanceof FallingBlock) {
    // Wait for it to fall, then break it, repeat upward
    // Max 10 iterations to prevent infinite gravel columns
}
```

**Cave Breach Handler:**
```java
// When tunnel exposes air (cave), assess:
// - Is the cave large? (scan for air blocks)
// - Are there mobs visible?
// - Is there lava?
// Response: Seal with cobblestone, continue tunnel
// Optional: Log cave location for player exploration later
```

---

## AI Tool Design

### create_mine Tool

```java
@ToolDefinition(
    name = "create_mine",
    description = "Create a long-term mine with shaft, staging hub, and branch mining. " +
                  "Digs a staircase shaft to optimal Y level, builds a staging room with " +
                  "chests/furnace/crafting table, then systematically branch mines for resources. " +
                  "Use this when the player asks to 'build a mine', 'create a mine', or 'set up mining'.",
    parameters = {
        @Param(name = "target", description = "What to mine for: 'diamond', 'iron', 'gold', 'all'. Default 'all' targets Y=-54 for maximum ore variety."),
        @Param(name = "branch_length", description = "Length of each branch tunnel. Default 32, max 64."),
        @Param(name = "branches_per_side", description = "Number of parallel branches per direction. Default 3."),
    }
)
```

**Target Y Mapping:**
- `"diamond"` / `"redstone"` → Y = -54 (just above lava lakes, max diamond)
- `"iron"` → Y = 16 (peak iron, also gets gold/lapis/copper)
- `"gold"` → Y = -16 (peak gold, also gets diamond/iron)
- `"all"` / `"general"` → Y = -54 (best variety: diamond, redstone, iron, gold, lapis)
- `"copper"` → Y = 48

### resume_mining Tool

```java
@ToolDefinition(
    name = "resume_mining",
    description = "Resume mining operations at an existing mine. " +
                  "The companion will return to the mine and continue from where it stopped.",
    parameters = {
        @Param(name = "mine_name", description = "Name of the mine to resume. Use 'list' to see all mines."),
    }
)
```

### Existing Tool Updates

**System Prompt Addition:**
```
MINE CREATION: When player asks to "create a mine", "build a mine", "set up a mine", 
or "start mining operations", use create_mine tool. This creates a full mine with:
- Staircase shaft to optimal Y level
- Staging hub with chests, furnace, crafting table
- Systematic branch mining tunnels
- Torch placement throughout
- Automatic ore collection and inventory management

For quick ore gathering (not a full mine), use mine_ores or strip_mine instead.

MINE HIERARCHY:
- create_mine: Full permanent mine structure (long-term, 10-30 minutes)
- strip_mine: Single tunnel at depth (medium-term, 2-5 minutes)
- mine_ores: Scan and collect nearby visible ores (short-term, 30 seconds)
```

---

## Implementation Priority

### Phase A — Foundation (implement first)
1. **PlacementHelper.java** — Block placement (torches, chests, furnaces)  
2. **MineState.java** — Mine data model (no persistence yet, in-memory)
3. **DigShaftTask.java** — Staircase shaft digging with torch placement
4. **CreateMineTool.java** — Basic AI tool wiring

### Phase B — Hub & Branches
5. **CreateHubTask.java** — Room clearing + furniture placement
6. **BranchMineTask.java** — Branch mining with poke holes + wall scanning
7. **MinePhase.java** — Phase state machine

### Phase C — Orchestration
8. **CreateMineTask.java** — Full orchestrator (shaft → hub → branches → deposit)
9. Inventory deposit runs (navigate to hub chest, deposit, return)
10. Falling block handling + cave breach sealing

### Phase D — Persistence & Polish (future)
11. NBT persistence of MineState (save/load across sessions)
12. ResumeMiningTool.java — Resume from saved state
13. Multi-level mines (dig deeper when level exhausted)
14. On-site smelting at hub furnace
15. Mine statistics reporting

---

## MineColonies Comparison

| Feature | MineColonies | MCAi Design | Notes |
|---------|-------------|------------|-------|
| Shaft type | Vertical + ladders | Staircase (walkable) | Safer for AI navigation |
| Level structure | Node graph (tunnel/crossroad/bend) | Branch mining from hub | Simpler, equally effective |
| Persistence | Full NBT w/ nodes | MineState NBT | Similar serialization |
| Ore mining | Mine ores at each node | Scan walls during branch + poke holes | More exposed = more ore |
| Blueprint system | Schematic-based clearing | Procedural clearing | Simpler, more flexible |
| Depth control | Building level gates depth | Target ore determines depth | Player/AI chooses |
| Hazard handling | Flood checking | Lava/gravel/cave/suffocation | Similar scope |
| Torch placement | Worker crafts + places | Auto-place every 8 blocks | Similar |
| Storage | Rack system at mine building | Chests at hub | Simpler |

**Key insight from MineColonies:** Their mine works because of **node-based expansion** — each completed tunnel opens up new possible tunnels. Our branch mine is simpler but achieves 90%+ ore coverage with proper spacing.

---

## Task Dependency Graph

```
create_mine (AI tool)
    │
    ▼
CreateMineTask (orchestrator)
    │
    ├──► DigShaftTask
    │       Uses: breakBlock(), placeTorch(), placeBlock() (cobblestone floor)
    │       Safety: isSafeToMine(), falling block check
    │       Output: Shaft from surface to target Y
    │
    ├──► CreateHubTask  
    │       Uses: breakBlock(), placeChest(), placeFurnace(), placeCraftingTable(), placeTorch()
    │       Output: 7×5×4 room with furniture
    │
    ├──► BranchMineTask (×4 directions, ×3 parallel branches each)
    │       Uses: breakBlock(), placeTorch(), scanForOres()
    │       Safety: isSafeToMine(), falling block check, cave breach seal
    │       Features: Poke holes, wall scanning, ore side-trips
    │       Inventory: Deposit when 80% full
    │
    └──► (loop back to DigShaftTask for next level)
```

---

## Estimated Scope

| Component | New Lines | Complexity | Priority |
|-----------|----------|------------|----------|
| PlacementHelper | ~150 | Low | A (required) |
| MineState + MineLevel + MineBranch | ~200 | Medium | A |
| DigShaftTask | ~250 | Medium | A |
| CreateHubTask | ~180 | Medium | B |
| BranchMineTask | ~350 | High | B |
| CreateMineTask (orchestrator) | ~200 | High | C |
| CreateMineTool | ~150 | Low | A |
| MinePhase enum | ~30 | Low | B |
| Falling block handler | ~50 | Low | B |
| Cave breach handler | ~50 | Medium | C |
| NBT persistence | ~100 | Medium | D |
| ResumeMiningTool | ~80 | Low | D |
| System prompt updates | ~30 | Low | A |
| **Total** | **~1,820** | | |

---

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| Pathfinding fails in staircase | High | Wider staircase (2×2), fallback teleport |
| Companion gets lost in mine | High | Store breadcrumb positions, teleport failsafe |
| Lava behind walls | Medium | isSafeToMine + block-ahead checking |
| Gravel buries companion | Medium | Existing suffocation handler + gravity block detection |
| Inventory full mid-branch | Medium | 80% threshold deposit runs |
| Torch supply runs out | Low | Auto-craft from coal + sticks |
| Task timeout (5 min) | Medium | Phase-based resumption (each phase is own task) |
| Companion dies | High | Mine state persists, resume from last phase |

---

## Open Questions

1. **Staircase vs Spiral**: Staircase is simpler but goes further from entrance as it goes down. Spiral (3×3) stays local but requires turn logic. Start with staircase?

2. **Block Placement API**: Does NeoForge 1.21.1 allow item usage simulation for the companion entity? Need to verify we can place chests/torches as the companion.

3. **Mine Name/Location Storage**: Per-player? Global? NBT on companion entity or separate data file?

4. **Maximum Mine Depth vs. Task Timeout**: A full mine to Y=-54 from surface (~120 blocks down) needs ~120 staircase steps. At ~1-2 seconds per block, that's 2-4 minutes just for the shaft. May need to split shaft digging into segments with intermediate completion events.

5. **Multi-mine support**: Should the companion support multiple mine locations? Start with single mine per player.
