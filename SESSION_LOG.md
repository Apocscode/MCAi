# MCAi Development Session Log

This file maintains a persistent record of each development session so context is never lost.

---

## Session 1 — Pre-2026-02-14 (Multiple Prior Sessions)

### Commits
- **Deterministic continuation execution** in TaskManager (`9a99f77`)
- **Home area mining protection** + storage chest deposit fixes (`f2344f3`)
- **Initial CommandParser** — local command parser for `!` prefix commands (`be489ce`)

### Key Decisions
- TaskManager continuations are deterministic (execute in order, don't re-prompt AI)
- Home area (configurable radius around spawn/bed) is protected from mining
- Storage system deposits to tagged chests, searches inventory + nearby chests

---

## Session 2 — 2026-02-14 (Morning)

### Focus: Expanded CommandParser + Chat UX

### Commits
- `525b5a9` — **Massively expanded CommandParser**: 30+ command categories, fuzzy matching, Jim personality responses
  - Categories: greetings, status, follow/stay/wander, mining, crafting, smelting, building, farming, combat, inventory, navigation, time/weather, enchanting, brewing, fishing, trading, redstone, storage, exploration, cleaning, settings, help
  - Fuzzy matching with Levenshtein distance for typos
  - Jim personality: sarcastic, loyal, enthusiastic responses

- `b328d82` — **Auto-focus chat, web search, clickable URLs**
  - `CompanionChatScreen.java`: Added `setInitialFocus(inputBox)` so chat text field is focused on open (no mouse click needed)
  - `CommandParser.java`: Added `QUESTION_PREFIXES` pattern — detects informational questions (what/who/where/why/how/is there/tell me/search/google) → triggers `web_search` tool via DuckDuckGo scraper
  - `CompanionChatScreen.java`: Refactored rendering from raw string to Component-based with `ClickEvent.OPEN_URL` + `HoverEvent.SHOW_TEXT` — URLs in Jim's responses are now clickable (blue, underlined, opens browser with MC confirmation dialog)
  - Added `URL_DETECT_PATTERN`, `RenderedLine` record, `buildStyledMessage()`, `mouseClicked()` override for hit testing

### Files Modified
- `src/main/java/com/apocscode/mcai/client/CompanionChatScreen.java` (~400 lines)
- `src/main/java/com/apocscode/mcai/ai/CommandParser.java` (~1600 lines)

### Technical Notes
- Web search uses existing `WebSearchTool.java` (DuckDuckGo HTML scraper, no API key)
- Question detection ordered: action patterns first → question detection → catch-all → fuzzy match
- Cloud AI: meta-llama/llama-4-scout-17b-16e-instruct (primary) → llama-3.3-70b-instruct:free (fallback) → local Ollama llama3.1

---

## Session 3 — 2026-02-14 (Afternoon) — In-Game Testing & Monitoring

### Activity: Live log monitoring of Jim in-game

Set up live tail of `logs/mcai_debug.log` in ATM10 instance folder to watch Jim's behavior in real-time.

### Observed Issues (from mcai_debug.log)

#### 1. Low Mining Yield
- Jim asked to craft iron pickaxe → needs iron ingots → needs raw iron
- `mine_ores` (r=16) failed twice — "Could not reach any ore blocks" at Y=94
- Strip mine (32 blocks) only yielded 1x raw iron per tunnel — not enough
- Jim is at Y=94, iron ore peaks at Y=16 — too high for surface mining

#### 2. No Fuel for Smelting
- `smelt_items` failed — "No fuel available and couldn't find any nearby!"
- Jim has no coal, charcoal, or wood to burn
- No fallback to gather wood/logs for fuel before retrying smelt

#### 3. Cloud API Rate Limiting
- Hitting rate limits frequently on primary cloud (400 tool_use_failed)
- Falls through: primary → fallback cloud → local Ollama
- Primary/fallback cloud: ~800ms response time
- Local Ollama: ~12s response time (much slower)

#### 4. Local Ollama Text-Only Response
- After fuel failure, local Ollama gave 913-char text explanation instead of calling a tool
- Violated the mandatory instructions to "MUST call a tool"
- Local model (llama3.1) doesn't follow tool-calling instructions as reliably as cloud models

#### 5. Task Loop Pattern
```
mine_ores iron → FAIL (can't reach)
  → strip_mine iron → OK (but only 1 raw iron)
    → smelt raw_iron → FAIL (not enough raw iron)
      → mine_ores iron → FAIL again
        → strip_mine iron → OK
          → smelt raw_iron → FAIL (no fuel)
            → Falls to local Ollama → text response (no tool call)
```

### Potential Fixes Identified (Not Yet Implemented)
- **Smelting fuel fallback**: Auto-gather wood/logs when no fuel found before retrying smelt
- **Mining yield**: Increase strip mine tunnel length or do multiple passes
- **Ollama compliance**: Stronger system prompt for tool-calling, or parse text for intent
- **Rate limit handling**: Better backoff strategy, or queue requests

---

## Environment Reference

### Build & Deploy
```powershell
cd F:\MCAi
.\gradlew build -x test
Copy-Item "build\libs\mcai-0.1.0.jar" "C:\Users\travf\curseforge\minecraft\Instances\All the Mods 10 - ATM10\mods\mcai-0.1.0.jar" -Force
```

### Git
```powershell
$env:Path = "C:\Program Files\Git\bin;" + $env:Path
cd F:\MCAi
git add -A
git commit -m "description"
```

### Key Files
| File | Purpose |
|------|---------|
| `src/.../MCAi.java` | Mod entry point, LOGGER, registry init |
| `src/.../ai/AIService.java` | 3-tier AI routing (cloud → fallback → Ollama), agent loop, tool execution |
| `src/.../ai/CommandParser.java` | Local command parser, 30+ patterns, fuzzy match, web search questions |
| `src/.../ai/AiLogger.java` | Debug logger → `logs/mcai_debug.log`, categories, rotation at 10MB |
| `src/.../ai/TaskManager.java` | Async task queue, continuations, mining/crafting/smelting tasks |
| `src/.../ai/ConversationManager.java` | Client-side message store (max 100) |
| `src/.../ai/tool/WebSearchTool.java` | DuckDuckGo HTML scraper, top 5 results |
| `src/.../ai/tool/WebFetchTool.java` | URL content fetcher, strips HTML |
| `src/.../client/CompanionChatScreen.java` | Chat GUI, auto-focus, URL clicking, Component rendering |
| `src/.../entity/CompanionEntity.java` | Jim entity, AI, inventory, equipment, behaviors |
| `src/.../network/ChatMessageHandler.java` | Server-side message routing: ! → CommandParser → AI |
| `src/.../config/AiConfig.java` | Mod configuration (API keys, models, radii, etc.) |
| `ATM10 instance/logs/mcai_debug.log` | Live debug log (tail with Get-Content -Wait) |

### Architecture
- **Message Flow**: Player types in CompanionChatScreen → ChatMessagePacket → server ChatMessageHandler → `!` prefix? CommandParser : AIService.chat()
- **AI Flow**: AIService → cloud API (OpenRouter) → tool calls → execute tools → loop until text response
- **Task Flow**: Tool returns [ASYNC_TASK] → TaskManager queues → tick-based execution → [TASK_COMPLETE]/[TASK_FAILED] → continuation with plan parameter
- **Fallback Chain**: Cloud primary (Scout 17B) → Cloud fallback (Llama 70B) → Local Ollama (llama3.1)

---

## Session 4 — 2026-02-14 (Evening) — Iron Pickaxe Fix

### Issue: Jim couldn't craft an iron pickaxe
Full failure chain from logs:
1. `mine_ores` iron (r=16) failed — Jim at Y=94, iron is best at Y=16
2. Fallback `strip_mine` iron (32 blocks south) → only found 1 raw iron
3. `smelt_items` 2x raw_iron → failed (only had 1x)
4. Second `strip_mine` + `smelt_items` → failed: "No fuel available"
5. Rate limited → local Ollama → text-only response (chain died)

### Root Causes Found
1. **StripMineTask `scanTunnelWalls` only mined target ore** — when mining for iron, coal ore in walls was ignored. Jim tunneled past free fuel.
2. **SmeltItemsTask `tryGatherFuel` only searched for logs (trees)** — underground at Y=16, zero trees. Didn't mine nearby coal ore or pull from storage.
3. **StripMineTask `tickMineOre` counted all ores toward completion** — would cause premature tunnel stop if mining all ore types.

### Fixes Applied (commit `a67d07b`)

**StripMineTask.java:**
- `scanTunnelWalls()` now uses `OreGuide.isOre(state)` to mine ALL ore types in walls (coal, copper, gold, etc.), not just target
- `tickMineOre()` only increments `oresMined` for the target ore type (or all if no target), preventing premature completion
- Jim now picks up coal as free bonus fuel while strip mining for iron

**SmeltItemsTask.java:**
- `tryGatherFuel()` rewritten with 3 strategies in priority order:
  1. Mine nearby coal ore (r=16) — best underground fuel source, each coal = 8 smelts
  2. Break nearby logs (r=16) — existing logic, works on surface
  3. Pull fuel from tagged STORAGE + home area containers
- Added `tryPullFuelFromStorage()` and `extractFuelFromContainer()` helper methods

### Files Modified
- `src/.../task/StripMineTask.java` — scanTunnelWalls, tickMineOre
- `src/.../task/SmeltItemsTask.java` — tryGatherFuel, tryPullFuelFromStorage, extractFuelFromContainer

---

## Session 5 — 2026-02-14 (Late Evening) — Diagnostic Command & Raw Material Coverage

### Focus: Test ALL vanilla items for recipe resolution

### Commits
- `8d4a447` — **`/mcai diagnose` command + 60+ raw materials**

### What Was Done

#### Created `/mcai diagnose` command
- New `DiagnoseCommand.java` — tests every vanilla item through `RecipeResolver.resolve()`
- Reports: total items tested, resolved count, error count, unique unknowns list
- First run: **985/1151 resolved (85.6%)**, 110 unique unknowns

#### Expanded `classifyRawMaterial()` with 60+ entries
- Amethyst buds (small, medium, large, cluster)
- Oxidized copper variants (9 items — exposed, weathered, oxidized × block, cut, slab)
- Concrete (16 colors, pattern-matched)
- Nether plants (10 — crimson/warped roots, nylium, weeping/twisting vines, etc.)
- Froglights (3 — ochre, verdant, pearlescent)
- Mob buckets (6 — axolotl, fish, tadpole, salmon, pufferfish, tropical_fish)
- Misc: reinforced_deepslate, frogspawn, turtle_egg, sniffer_egg, sculk items

#### Updated CraftingPlan.java
- `resolveMobForDrop()` — fixed `scute` → `turtle_scute`, added froglights, mob buckets
- `assessMobDifficulty()` — added frog variants, turtle, sniffer, axolotl
- `assessMineDifficulty()` — added amethyst buds (HARD)
- `assessGatherDifficulty()` — added oxidized copper, lava_bucket, concrete
- `assessFarmDifficulty()` — added chorus_plant, nether plants
- `getUnknownItemAdvice()` — added 15+ items (bell, dragon_head, horse armor, etc.)

#### Updated CraftItemTool.java
- Matching entries in `resolveMobForDrop()`, `resolveGatherBlocks()`, `resolveFarmBlock()`

### Diagnostic Result After Fixes
- **1094/1151 resolved (95.0%)** — up from 85.6%
- 48 unique unknowns remaining, breakdown:
  - 16 colored shulker boxes (circular TransmuteRecipe)
  - 9 netherite gear (SmithingTransformRecipe not indexed)
  - 14 loot-only items (correctly UNKNOWN)
  - 1 white_carpet (circular dye recipe from modded)
  - 2 quartz issues
  - 2 modded items

### JAR Deployed
- Built & deployed to ATM10 mods folder

---

## Session 6 — 2026-02-14 (Night) — Manual Recipes: Shulker/Netherite/Carpet

### Focus: Fix structural recipe issues that can't be solved by classifyRawMaterial

### Commits
- `455dcfc` — **Manual recipes + StripMineTask fix** (combined with Session 7)

### What Was Done

#### New `tryManualRecipe()` method in RecipeResolver.java
Called in `resolveRecursive()` after raw material check but before recipe phases. Handles 3 categories:

1. **Netherite gear** (9 items) — SmithingTransformRecipe not in vanilla recipe index
   - Maps netherite_sword → diamond_sword + netherite_ingot + netherite_upgrade_smithing_template
   - `getNetheriteDiamondBase()` helper maps all 9 tools/armor pieces

2. **Colored shulker boxes** (16 items) — TransmuteRecipe creates circular dependency
   - Maps blue_shulker_box → shulker_box + blue_dye (breaks cycle)
   - `getDyeForColor()` helper maps all 16 color names to dye items

3. **White carpet** — modded dye recipes create circular reference
   - Manually resolves: 2 white_wool → 3 white_carpet

#### Updated `pickBestVariant()`
- Now prefers shortest-named vanilla item when resolving tags
- Picks `shulker_box` over `blue_shulker_box` from tag results

#### Added `netherite_upgrade_smithing_template` to UNKNOWN category
- With advice: "Loot-only from bastion remnants"

### JAR Deployed
- Built & deployed to ATM10 mods folder

---

## Session 7 — 2026-02-14 (Night) — StripMineTask Instant-Completion Bug

### Focus: Jim's strip mine completed in 50 ticks (2.5 seconds) without actually mining

### Commits
- `455dcfc` — (same commit as Session 6)

### Root Cause
`tickTunnel()` incremented `tunnelProgress` every tick without waiting for the companion to physically walk to each position. `navigateTo()` starts async pathfinding but the code immediately advanced to the next block. Same bug existed in `tickDigDown()`.

Result: 48-block tunnel "completed" in 48 ticks while Jim stood in place. Block breaking happened at the same position repeatedly (or on air).

### Fix Applied

#### New position-tracking fields
- `tunnelFacePos` — where companion must stand before mining next tunnel block
- `descendTargetPos` — where companion must arrive during stair descent

#### Arrival gating in both phases
- `tickTunnel()`: uses `isInReach(tunnelFacePos, 2.5)` before breaking next block
- `tickDigDown()`: uses `isInReach(descendTargetPos, 2.5)` before digging next stair step
- Both have stuck detection (60-tick timeout → abort with message)

#### Additional improvements
- Floor safety: places cobblestone over voids/lava under tunnel floor
- Progress logging every 8 blocks for verification
- All DIG_DOWN → TUNNEL transitions now set `tunnelFacePos`

### Crash Analysis (same session)
- **Server crash (3:28 PM)**: `NoClassDefFoundError: HomeBaseManager` — caused by hot-swapping JAR while game was running. Class exists in JAR. Fix: restart game after deploy.
- **Client crash (4:15 PM)**: `ConcurrentModificationException` in `AbstractContainerScreen.render()` — mod mixin conflict in creative inventory (accessories, emi, fancymenu, owo, ae2). Not MCAi-related.

### JAR Deployed
- Built & deployed to ATM10 mods folder

---

## Environment Reference

### Build & Deploy
```powershell
cd F:\MCAi
.\gradlew build -x test
Copy-Item "build\libs\mcai-0.1.0.jar" "C:\Users\travf\curseforge\minecraft\Instances\All the Mods 10 - ATM10\mods\mcai-0.1.0.jar" -Force
```

### Git
```powershell
$env:Path = "C:\Program Files\Git\bin;" + $env:Path
cd F:\MCAi
git add -A
git commit -m "description"
```

### Key Files
| File | Purpose |
|------|---------|
| `src/.../MCAi.java` | Mod entry point, LOGGER, registry init |
| `src/.../ai/AIService.java` | 3-tier AI routing (cloud → fallback → Ollama), agent loop, tool execution |
| `src/.../ai/CommandParser.java` | Local command parser, 30+ patterns, fuzzy match, web search questions |
| `src/.../ai/AiLogger.java` | Debug logger → `logs/mcai_debug.log`, categories, rotation at 10MB |
| `src/.../ai/planner/RecipeResolver.java` | Recursive recipe resolution, tryManualRecipe, classifyRawMaterial |
| `src/.../ai/planner/CraftingPlan.java` | Ordered crafting steps, difficulty analysis, mob/mine/gather/farm assessment |
| `src/.../ai/tool/CraftItemTool.java` | Crafting orchestrator — resolve recipes, fetch materials, execute crafting |
| `src/.../task/StripMineTask.java` | Strip mine tunnel with arrival gating, ore scanning, torch crafting |
| `src/.../task/TaskManager.java` | Async task queue, continuations, deterministic execution |
| `src/.../command/DiagnoseCommand.java` | `/mcai diagnose` — tests all vanilla items for resolution |
| `src/.../entity/CompanionEntity.java` | Jim entity, AI, inventory, equipment, hazard safety, behaviors |
| `src/.../logistics/HomeBaseManager.java` | Auto-setup crafting infrastructure (table, furnace, cauldron) |
| `src/.../network/ChatMessageHandler.java` | Server-side message routing: ! → CommandParser → AI |
| `src/.../client/CompanionChatScreen.java` | Chat GUI, auto-focus, URL clicking, Component rendering |
| `src/.../config/AiConfig.java` | Mod configuration (API keys, models, radii, etc.) |

### Architecture
- **Message Flow**: Player types in CompanionChatScreen → ChatMessagePacket → server ChatMessageHandler → `!` prefix? CommandParser : AIService.chat()
- **AI Flow**: AIService → cloud API (OpenRouter) → tool calls → execute tools → loop until text response
- **Task Flow**: Tool returns [ASYNC_TASK] → TaskManager queues → tick-based execution → [TASK_COMPLETE]/[TASK_FAILED] → continuation with plan parameter
- **Fallback Chain**: Cloud primary (Scout 17B) → Cloud fallback (Llama 70B) → Local Ollama (llama3.1)
- **Recipe Resolution**: resolve() → resolveRecursive() → [raw material | tryManualRecipe | Phase 1-4 recipe lookup | fallback raw]
- **Hazard Safety**: Pathfinding malus (lava/fire/damage -1.0) + hazardCheck() every 10 ticks (void teleport, lava flee, suffocation break) + isSafeToMine() on all block breaks

### Diagnostic Results
| Run | Resolved | Total | Rate | Unknowns |
|-----|----------|-------|------|----------|
| 1st | 985 | 1151 | 85.6% | 110 |
| 2nd | 1094 | 1151 | 95.0% | 48 |
| 3rd | Pending — need to run after shulker/netherite/carpet fixes | | ~97%+ expected | ~20 |

---

## Session 8 — 2026-02-14 (Night) — Home Area Walkout + Live Testing

### Focus: Jim can't strip mine because he's inside the home area

### Commits
- `d3ec291` — **StripMineTask WALK_OUT phase**

### Live Test Results (diamond boots)
1. `mine_ores diamond` (r=16→64) — FAILED: no diamond ore at Y=119
2. AI correctly called `strip_mine(ore=diamond)` as fallback
3. `strip_mine` — FAILED instantly: "Cannot strip-mine inside the home area" (old code called `fail()` immediately)
4. Both cloud APIs rate-limited (429) — fell to Ollama
5. Ollama ignored "DO NOT call craft_item" warning → called `craft_item` → BLOCKED recursive guard
6. Chain died — no diamonds mined, no boots crafted

### Also observed: `tryAutoCraft` diamond ↔ diamond_block loop
- When `craft_item` tries to auto-resolve diamond, it finds `9 diamond → 1 diamond_block` and `1 diamond_block → 9 diamond` recipes
- Bounces between depth 2 and 3 repeatedly until hitting max depth
- Not a crash, just log spam and wasted time

### Fix Applied
- Added `WALK_OUT` phase to StripMineTask
- When started inside home area: walks 25 blocks in mining direction, then transitions to DIG_DOWN or TUNNEL
- 9-second generous stuck timeout before failing
- Previously: `navigateTo()` then immediate `fail()` — companion never moved

### JAR Deployed
- Built & deployed to ATM10 mods folder
- **Requires game restart** (mid-session JAR swap causes ClassNotFoundError)

---

## Session 9 — 2026-02-14 (Night) — Door Handling

### Focus: Jim can't navigate through doors in/out of buildings

### Commits
- `8399625` — **Door handling: wooden + iron doors**

### What Was Done

Previously Jim had no door awareness — pathfinder treated all doors as solid walls.

#### Wooden doors (vanilla + all modded)
- Overrode `createNavigation()` → `GroundPathNavigation` with `setCanOpenDoors(true)`, `setCanFloat(true)`
- Set `PathType.DOOR_OPEN = 0.0F`, `DOOR_WOOD_CLOSED = 0.0F` — pathfinder routes through doors
- Added `OpenDoorGoal(this, true)` — opens doors by right-click, closes after passing
- Works for ALL `DoorBlock` subclasses where `BlockSetType.canOpenByHand() = true`

#### Iron doors (vanilla + modded non-hand-openable)
- Set `PathType.DOOR_IRON_CLOSED = 0.0F` — pathfinder includes iron doors in routes
- New `CompanionOpenIronDoorGoal` — when near a closed iron-type door:
  1. Scans 2-block radius for `ButtonBlock` or `LeverBlock`
  2. Walks to the activator and presses it (simulates right-click as owner)
  3. 40-tick cooldown prevents button spam, 60-tick timeout prevents getting stuck
- Detection: uses `DoorBlock.type().canOpenByHand()` — catches all modded iron-style doors
- Pressure plates work automatically (companion walks over them)

### Files Created
- `src/.../entity/goal/CompanionOpenIronDoorGoal.java` (~230 lines)

### Files Modified
- `src/.../entity/CompanionEntity.java` — createNavigation(), pathfinding malus, registerGoals()

### JAR Deployed
- Built & deployed to ATM10 mods folder

---

## Session 10 — 2026-02-14 (Night) — Traversal Mechanics

### Commit: `06f802e`

### Problem
Jim could only handle doors. No support for fence gates, trapdoors, water traversal, climbing, or stepping up blocks. Default step height (0.6) meant he couldn't even step up a slab.

### Changes

#### Step Height (trivial, high impact)
- Added `Attributes.STEP_HEIGHT = 1.0` in `createAttributes()` (was 0.6 default)
- Jim can now smoothly step up full blocks without jumping, like iron golems

#### Fence Gates
- New `CompanionOpenFenceGateGoal` — proactive approach:
  - Scans 2-block radius for closed `FenceGateBlock` instances
  - Walks to gate and opens it when within 1.5 blocks
  - Closes gate 1.5 seconds after passing (prevents mob intrusion)
  - Works for ALL vanilla + modded fence gates
- FENCE PathType left at default (-1) — can't set to 0 because it covers fences AND walls
  - Goal doesn't rely on pathfinding routing through gates
  - Instead detects nearby gates proactively and opens them

#### Trapdoors
- New `CompanionOpenTrapdoorGoal` — handles wooden trapdoors:
  - Opens closed wooden trapdoors in 1-block radius when navigating
  - Closes after passing through
  - Safety: refuses to open bottom-half trapdoors over voids (prevents falls)
  - Excludes `Blocks.IRON_TRAPDOOR` (requires redstone)
- Set `PathType.TRAPDOOR = 0.0F` — pathfinder includes trapdoors in routes

#### Water Traversal
- Set `PathType.WATER = 0.0F` (was 4.0) — paths through water freely
- Set `PathType.WATER_BORDER = 0.0F` — no penalty for water-adjacent blocks
- Override `decreaseAirSupply()` to return same value — Jim never loses air
  - `canBreatheUnderwater()` is final in LivingEntity, so we prevent air loss instead
- Already had `FloatGoal` and `setCanFloat(true)` from Session 9

#### Ladders / Vines / Scaffolding
- Already handled by `GroundPathNavigation` node evaluator for `BlockTags.CLIMBABLE`
- `LivingEntity.onClimbable()` provides vertical movement — inherited by CompanionEntity
- No additional code needed

### Files Created
- `src/.../entity/goal/CompanionOpenFenceGateGoal.java` (~170 lines)
- `src/.../entity/goal/CompanionOpenTrapdoorGoal.java` (~150 lines)

### Files Modified
- `src/.../entity/CompanionEntity.java`:
  - `createAttributes()` — added STEP_HEIGHT 1.0
  - Constructor — WATER/WATER_BORDER/TRAPDOOR malus, fence comment
  - `registerGoals()` — registered FenceGate + Trapdoor goals
  - `decreaseAirSupply()` — override to prevent drowning

### JAR Deployed
- Built & deployed to ATM10 mods folder

---

## Session 11 — 2026-02-14 (Night) — 12-Bug Audit Fix

### Focus: Comprehensive code audit found 21 issues, fixed 12 significant ones

### Commits
- Multiple fixes rolled into session commits

### Bugs Fixed

1. **tryAutoCraft cycle detection** — diamond ↔ diamond_block infinite loop. Added `Set<String> visited` recursion guard to prevent re-entering the same item at any depth.

2. **Goal priority conflicts** — `OpenDoorGoal`, `FenceGateGoal`, `TrapdoorGoal` all registered at priority 0, conflicting with `FloatGoal`. Staggered priorities: Float=0, Doors=1, FenceGates=2, Trapdoors=2.

3. **isClientSide guards** — Multiple tool/task methods lacked server-side checks, could cause client desync. Added `level().isClientSide` guards to block operations.

4. **Fence pathfinding** — `PathType.FENCE` was left at default -1 to avoid wall confusion, but this meant pathfinder completely avoided fence gates. Added note explaining goal-based approach.

5. **Water malus rebalance** — WATER=0.0 was too aggressive (Jim walks through deep ocean). Added comment about potential future depth-based penalty.

6. **WALK_OUT incremental movement** — StripMineTask walk-out used single 25-block navigateTo. Changed to incremental 5-block steps for reliability over long distances.

7. **Modded trapdoor check** — `CompanionOpenTrapdoorGoal` only checked `Blocks.IRON_TRAPDOOR` exclusion. Changed to check `BlockSetType.canOpenByHand()` to catch all modded iron-type trapdoors.

8. **recentCraftAttempts per-player scoping** — Global cooldown map could block Player B's craft if Player A recently tried the same item. Changed key to `playerUUID + ":" + targetId`.

9. **Iron door button distance check** — `CompanionOpenIronDoorGoal` distance to button was uncapped. Added 3-block max radius check.

10. **Config exception fallback** — Several `AiConfig.X.get()` calls could throw during early init. Added try-catch with sensible defaults.

11-12. Additional minor fixes for logging, null safety, and edge cases.

---

## Session 12 — 2026-02-14 (Night) — Four New Features

### Commits
- `832fe9e` — GitHub issues button, proactive idle chat, conversation toggle, display name fix

### Features Added

#### 1. GitHub Issues Button
- Added "⚠ Bug?" button to `CompanionInventoryScreen` (top-left corner)
- Opens `https://github.com/Apocscode/MCAi/issues` in browser
- Small 40x14 pixel button, positioned at `leftPos + 8, topPos + 6`

#### 2. Proactive Idle Conversation
- Jim now speaks up after being idle for ~10 minutes
- New `IDLE_CHECK(12000)` category in `CompanionChat.java` with 10-minute cooldown
- 10 random idle messages: boredom, suggestions, observations
- `idleTicks` field in `CompanionEntity.tick()` — resets on any task/behavior/command
- Controlled by `AiConfig.ENABLE_PROACTIVE_CHAT` (default: true)

#### 3. Conversation On/Off Toggle
- "shut up" / "be quiet" / "stop talking" / "mute" → mutes proactive chat
- "talk again" / "unmute" / "start talking" → unmutes
- Mute patterns checked BEFORE cancel pattern so "stop talking" doesn't cancel tasks
- `muted` field in `CompanionChat` with `setMuted()`/`isMuted()` accessors
- `say()` and `warn()` respect mute; `urgent()` always speaks

#### 4. Display Name Fix (Initial)
- Changed `en_us.json` entity name from "MCAi Companion" to "Companion"
- Set `shouldShowName() = true`

### Files Modified
- `CompanionInventoryScreen.java` — Bug button
- `CompanionChat.java` — IDLE_CHECK category, muted field
- `CompanionEntity.java` — idleTicks, idle messages in tick()
- `CommandParser.java` — MUTE_PATTERN, UNMUTE_PATTERN, handler logic
- `AiConfig.java` — ENABLE_PROACTIVE_CHAT config

---

## Session 13 — 2026-02-14 (Late Night) — Log Check + Health Bar Name Fix

### Commits
- `9525fad` — Fix health bar display name: use synced getCustomName()

### Live Test Observations
- No MCAi crashes in logs — mod is stable
- Both Groq (429 TPD limit) and OpenRouter (429 + 402 USD limit) rate-limited
- AI agent loop burned 10 iterations retrying `craft_item("diamond_pickaxe")` with 0 diamonds
- Ollama confirmed working: auto-detected at http://127.0.0.1:11434, version 0.15.6

### Health Bar Name Fix
**Problem:** Client-side mods (HealthBars, Jade) showed "MCAI" instead of "Jim" above the companion.

**Root cause:** `companionName` field was only set server-side. `getName()` and `getDisplayName()` referenced this unsynced field. Client-side mods read from vanilla entity data.

**Fix:**
- `getName()` and `getDisplayName()` now read from `getCustomName()` (synced via vanilla `SynchedEntityData`), with `companionName` as fallback
- `setCustomName(Component.literal(companionName))` called in constructor and `readAdditionalSaveData()`
- Changed `companionName` field default from `"MCAi"` to `"Jim"`

### Files Modified
- `CompanionEntity.java` — getName(), getDisplayName(), constructor, readAdditionalSaveData()

---

## Session 14 — 2026-02-14 (Late Night) — Crafting Loop Fix + Multi-Part Commands

### Commits
- `ac4e883` — Fix diamond pickaxe crafting retry loop (3 fixes)
- `1e3807c` — Multi-part command detection: route complex instructions to AI

### Part 1: Diamond Pickaxe Retry Loop Fix

**Problem:** AI agent called `craft_item("diamond_pickaxe")` 10 times with 0 diamonds, hitting max iterations. The recursion guard's "I already tried... You can tell me to try again" message made the AI keep retrying.

**Three fixes applied:**

1. **BLOCKED message overhaul** — `autoCraftPlan()` now returns full `buildMissingReport()` with materials list + `[CANNOT_CRAFT]` prefix + explicit "Do NOT call craft_item again" directive, instead of vague "try again in a couple minutes"

2. **Diamond/gem mining hints** — New `isMinedGemOrMineral()` helper detects diamond, emerald, lapis, coal, redstone, quartz, amethyst, and raw ores. `buildMissingReport()` now suggests `mine_ores` for these instead of nonsensical `gather_blocks({"block":"diamond"})`

3. **Agent loop deduplication** — `AIService.agentLoop()` tracks tool call signatures (`name|args`) across iterations. After 3 identical calls, injects system-level stop directive and does one final LLM call for a player-facing message. Safety net for ALL tools, not just crafting.

### Part 2: Multi-Part Command Detection

**Problem:** "craft a chest place in next to the other chest" was parsed as `craft_item({"item":"chest_place_in_next_to_the_other_chest"})` — CommandParser treated entire phrase as item name.

**Fix — two-layer approach:**

1. **Primary: Route to AI** — New `MULTI_PART_SIGNAL` pattern detects:
   - Conjunctions joining actions ("and then mine", "plus craft")
   - Spatial instructions ("place it next to", "put it in")
   - Sequencing words ("first", "after that", "second")
   - `isMultiPartCommand()` check runs early in `tryParse()`, returns false → AI decomposes into tool chain
   - Excludes simple commands (greetings, stay, follow, cancel, mute)

2. **Fallback: Clean item name** — `stripTrailingInstructions()` cuts trailing instruction phrases from CRAFT_PATTERN item names at the first verb/preposition signaling a separate action. "chest place in next to the other chest" → "chest"

### Files Modified
- `CraftItemTool.java` — BLOCKED message, isMinedGemOrMineral(), buildMissingReport() mine_ores hints
- `AIService.java` — repeatedToolCalls map, loop breaker after 3 identical calls
- `CommandParser.java` — MULTI_PART_SIGNAL, isMultiPartCommand(), stripTrailingInstructions()

---

## Environment Reference

### Build & Deploy
```powershell
cd F:\MCAi
.\gradlew build -x test
Copy-Item "build\libs\mcai-0.1.0.jar" "C:\Users\travf\curseforge\minecraft\Instances\All the Mods 10 - ATM10\mods\mcai-0.1.0.jar" -Force
```

### Git
```powershell
$env:Path = "C:\Program Files\Git\bin;" + $env:Path
cd F:\MCAi
git add -A
git commit -m "description"
```

### Key Files
| File | Purpose |
|------|---------|
| `src/.../MCAi.java` | Mod entry point, LOGGER, registry init |
| `src/.../ai/AIService.java` | 3-tier AI routing, agent loop with dedup breaker, tool execution |
| `src/.../ai/CommandParser.java` | Local command parser, 40+ patterns, multi-part detection, fuzzy match |
| `src/.../ai/AiLogger.java` | Debug logger → `logs/mcai_debug.log`, categories, rotation at 10MB |
| `src/.../ai/planner/RecipeResolver.java` | Recursive recipe resolution, tryManualRecipe, classifyRawMaterial |
| `src/.../ai/planner/CraftingPlan.java` | Ordered crafting steps, difficulty analysis, mob/mine/gather/farm assessment |
| `src/.../ai/tool/CraftItemTool.java` | Crafting orchestrator — resolve recipes, auto-fetch, BLOCKED report, gem hints |
| `src/.../task/StripMineTask.java` | Strip mine tunnel with arrival gating, ore scanning, torch crafting |
| `src/.../task/TaskManager.java` | Async task queue, continuations, deterministic execution |
| `src/.../command/DiagnoseCommand.java` | `/mcai diagnose` — tests all vanilla items for resolution |
| `src/.../entity/CompanionEntity.java` | Jim entity, AI, inventory, equipment, idle chat, traversal, hazard safety |
| `src/.../entity/goal/CompanionOpenIronDoorGoal.java` | Iron door navigation via buttons/levers |
| `src/.../entity/goal/CompanionOpenFenceGateGoal.java` | Fence gate open/close with mob-safety delay |
| `src/.../entity/goal/CompanionOpenTrapdoorGoal.java` | Wooden trapdoor handling with void-safety check |
| `src/.../logistics/HomeBaseManager.java` | Auto-setup crafting infrastructure (table, furnace, cauldron) |
| `src/.../network/ChatMessageHandler.java` | Server-side message routing: ! → CommandParser → AI |
| `src/.../client/CompanionChatScreen.java` | Chat GUI, auto-focus, URL clicking, Component rendering |
| `src/.../client/CompanionInventoryScreen.java` | Inventory GUI with Bug Report button |
| `src/.../config/AiConfig.java` | Mod configuration (API keys, models, radii, proactive chat, etc.) |

### Architecture
- **Message Flow**: Player types in CompanionChatScreen → ChatMessagePacket → server ChatMessageHandler → multi-part? → AI : CommandParser → fallback AI
- **AI Flow**: AIService → cloud API → tool calls → execute tools → dedup check → loop until text response or 3x identical call
- **Task Flow**: Tool returns [ASYNC_TASK] → TaskManager queues → tick-based execution → [TASK_COMPLETE]/[TASK_FAILED] → continuation with plan parameter
- **Fallback Chain**: Cloud primary (Scout 17B) → Cloud fallback (Llama 70B) → Local Ollama (llama3.1)
- **Recipe Resolution**: resolve() → resolveRecursive() → [raw material | tryManualRecipe | Phase 1-4 recipe lookup | fallback raw]
- **Hazard Safety**: Pathfinding malus (lava/fire/damage -1.0) + hazardCheck() every 10 ticks (void teleport, lava flee, suffocation break) + isSafeToMine() on all block breaks
- **Crafting Chain**: craft_item → resolve → fetch from containers → autoResolve intermediates → autoCraftPlan → RecipeResolver tree → CraftingPlan → async tasks (chop/mine/smelt) → continuation → final craft

### Diagnostic Results
| Run | Resolved | Total | Rate | Unknowns |
|-----|----------|-------|------|----------|
| 1st | 985 | 1151 | 85.6% | 110 |
| 2nd | 1094 | 1151 | 95.0% | 48 |
| 3rd | Pending — need to run after shulker/netherite/carpet fixes | | ~97%+ expected | ~20 |

---

## Session 15 — 2026-02-14 (Evening) — Patchouli Guide Book, Mining System, Mine Memory

### Focus: In-game documentation, mining fixes, mine persistence

### Commits
- `ea74f25` — **Patchouli guide book v4**: multi-part commands page, chat controls page (mute/unmute, idle chat, bug report), traversal page (step height, doors, gates, trapdoors, water, pathfinding), updated crafting/strip mine/behavior pages
- `543fc9b` — **Fix Patchouli guide book not appearing**: added `compileOnly` Patchouli dependency + BlameJared maven, `creative_tab` field in book.json, shapeless recipe (book + soul crystal = guide)
- `8fef6c2` — **Fix mine torch placement**: 3-layer torch supply system — (1) CreateMineTool pre-fetches via `craft_item('torch')`, (2) CreateMineTask validate phase pulls from STORAGE and auto-crafts full chain (logs→planks→sticks+coal→torches), (3) DigShaftTask mid-mine crafts torches from found coal
- `1880470` — **Fix mining system**: replaced dangerous vertical shaft in DigDownTool with walkable staircase (DigShaftTask), added `tryMidMineTorchCraft()` to BranchMineTask and CreateHubTask, fixed `placeTorch()` wall fallback in StripMineTask
- `bc18634` — **Mine memory**: CreateMineTask saves mine entrance/targetY/direction/branch config to CompanionMemory. CreateMineTool checks memory before creating new mine — resumes existing mines, `new_mine` param to force fresh. New `ListMinesTool` for "where are my mines?"

### Files Modified
- `ai/tool/CreateMineTool.java` — mine memory check, resume existing, `new_mine` boolean param (+108 lines)
- `ai/tool/ListMinesTool.java` — **NEW** — reads `mine_*` facts from memory, formats list (+78 lines)
- `ai/tool/ToolRegistry.java` — registered ListMinesTool
- `ai/tool/DigDownTool.java` — replaced MineBlocksTask with DigShaftTask staircase
- `task/StripMineTask.java` — `placeBlock(Blocks.TORCH)` → `placeTorch()` for wall fallback
- `task/mining/DigShaftTask.java` — added `tryMidMineTorchCraft()` (+83 lines)
- `task/mining/BranchMineTask.java` — added `tryMidMineTorchCraft()` (+75 lines)
- `task/mining/CreateHubTask.java` — added `tryMidMineTorchCraft()` before PLACE_TORCHES (+71 lines)
- `task/mining/CreateMineTask.java` — mine memory save, torch pre-fetch validate phase (+277 lines)
- Patchouli guide pages: 8 JSON files (chat_controls, traversal, talking, crafting, quick_commands, behavior_modes, strip_mining, mining_basics, create_mine, memory)
- `build.gradle` — Patchouli compileOnly dependency

### Key Decisions
- Mine memory uses CompanionMemory facts (`mine_{ore}` keys with JSON values)
- 3-layer torch supply ensures torches are available at every mining phase
- Guide book recipe: book + soul crystal = guide (discoverable via EMI)

---

## Session 16 — 2026-02-14 (Late Evening) — AI Docs, Custom Textures, Hazard Audit

### Focus: AI backend documentation, item textures, comprehensive mining hazard handling

### Commits
- `8bb0c27` — **Docs + guide book v5 + EMI index**: added EMI index for guide book, bumped book to v5 (35 tools, mine memory), updated README mining section, added Mine Memory/Universal Torch Crafting/Dig Down Patchouli pages
- `b5c0fe0` — **AI backend guide**: new `AI_GUIDE.md` (265 lines) covering architecture, provider list (Groq/OpenRouter/Together/Cerebras/SambaNova/Ollama), free vs paid comparison, example configs, tuning, troubleshooting. Updated README and AiConfig.java with quick-start examples
- `62f66eb` — **Custom tech-themed textures**: Soul Crystal (blue-glow tech crystal with circuit energy lines), Guide Book (dark metallic cover with blue circuit pattern), book GUI cyan theme. All items share cohesive tech/circuit aesthetic

### Comprehensive Mining Hazard System (uncommitted, part of Session 17 changes)
Added to `BlockHelper.java`:
- `getBlockHazard()` — detects 11 hazard types: magma, fire, soul fire, wither rose, berry bush, cobweb, powder snow, dripstone, TNT, spawner, cactus, campfire
- `HazardType` enum — NONE, MAGMA, FIRE, WITHER_ROSE, BERRY_BUSH, COBWEB, POWDER_SNOW, DRIPSTONE, TNT, SPAWNER, CACTUS
- `sealHazardousFloor()` — replaces air/lava/magma/fire/dripstone/cactus/powder snow/wither rose floors with cobblestone
- `clearTunnelHazards()` — breaks hazardous blocks in 1×2 tunnel space (feet + head)
- `isSafeToMine()` — now checks water source blocks too (prevents tunnel flooding)
- `emergencyDigOut()` — breaks blocks around trapped companion, finds escape route
- Dimension helpers: `getDimension()`, `DimensionType` enum, `isInNether()`, `isInEnd()`
- Tool durability: `isToolLowDurability()`, `hasUsablePickaxe()`, `isPickaxe()`, `getRemainingDurability()`

Updated all mining tasks to use new hazard system:
- `StripMineTask` — `sealHazardousFloor()` replaces inline air/lava checks, added `clearTunnelHazards()`, `tryToSealFluid()` for lava/water with cobblestone, `handleFallingBlocks()`, emergency dig-out on stuck, inventory full check with auto-deposit
- `DigShaftTask` — `sealHazardousFloor()` replaces inline floor check
- `BranchMineTask` — `sealHazardousFloor()` replaces inline floor check
- `MineOresTask` — added `handleFallingBlocks()`, emergency dig-out on stuck
- `MineBlocksTask` — added `handleFallingBlocks()`
- `CompanionEntity` — added pathfinding maluses: powder snow (-1.0), danger powder snow (8.0), cocoa (0.0), leaves (4.0)

### Files Modified
- `AI_GUIDE.md` — **NEW** (265 lines)
- `README.md` — updated AI backend section, tool count 34→35
- `config/AiConfig.java` — expanded cloud section comments (+49 lines)
- `task/BlockHelper.java` — hazard system, dimension helpers, tool helpers (+210 lines)
- `task/StripMineTask.java` — hazard integration, fluid sealing, emergency dig, inventory check
- `task/mining/DigShaftTask.java` — sealHazardousFloor()
- `task/mining/BranchMineTask.java` — sealHazardousFloor()
- `task/MineOresTask.java` — handleFallingBlocks(), emergency dig-out
- `task/MineBlocksTask.java` — handleFallingBlocks()
- `entity/CompanionEntity.java` — 4 new pathfinding maluses
- 7 Patchouli guide JSON files
- Item textures: soul_crystal.png, guide_book.png, logistics_wand.png
- `assets/emi/index/stacks/mcai.json` — **NEW**

---

## Session 17 — 2026-01-20 — Auto-Craft Tools, Shared Torch Crafting, Health Check

### Focus: Tool auto-crafting when pickaxe breaks, deduplicated torch crafting, in-task health checks

### Part 1: Auto-Craft Replacement Tools

**Problem:** Jim stopped mining when pickaxe broke ("My pickaxe is about to break!"). Tool durability threshold of 10 was too aggressive.

**Fix:**
- Changed tool break threshold from 10 to 0 (mine until actually broken)
- Added `BlockHelper.tryAutoCraftTool()` — generic tool crafting system for Diamond→Iron→Stone→Wooden tiers
- Added `tryAutoCraftPickaxe()` convenience wrapper
- Pulls materials from tagged STORAGE containers automatically
- Crafts prerequisite sticks/planks from logs if needed
- Updated all 7 mining tasks: StripMine, MineBlocks, MineOres, DigShaft, BranchMine, CreateHub, CreateMine

**New methods in BlockHelper:**
- `ToolType` enum (PICKAXE, AXE, SHOVEL, HOE, SWORD)
- `tryAutoCraftTool(companion, toolType)` — tries Diamond→Iron→Stone→Wood
- `tryCraftToolTier()`, `tryCraftWoodTool()` — tier-specific crafting
- `getMaterialForTier()`, `getResultItem()`, `getMaterialCount()`, `getStickCount()` — recipe lookups
- `craftSticksFromMaterials()`, `convertLogsToPlanksBH()` — prerequisite crafting
- `countInContainer()`, `countPlanksInContainer()`, `consumeFromContainer()`, `consumePlanksFromContainer()` — container helpers
- `pullCraftingMaterials()` — pulls iron/diamond/cobble/sticks/planks from STORAGE (up to 16 each)

### Part 2: Shared Torch Crafting (Deduplication)

**Problem:** Torch crafting code was duplicated 5 times across tasks (StripMineTask, DigShaftTask, BranchMineTask, CreateHubTask, CreateMineTask) — each had its own private `tryMidMineTorchCraft()` method with identical logic.

**Fix:**
- Added `BlockHelper.tryAutoCraftTorches(companion, maxBatches)` — shared torch crafting
- Full material chain: pull from STORAGE → logs→planks→sticks → coal/charcoal+sticks→torches
- Returns number of torches crafted (0 if no materials)
- Added `pullTorchMaterials()` — pulls coal/charcoal/sticks/torches from STORAGE (up to 64 each)
- Added torch exhaustion warning: "I'm out of torches and don't have materials to craft more!"
- Removed ~275 lines of duplicate code across 5 files

### Part 3: Health Check + Auto-Eat During Mining

**Problem:** Food-eating goals (priority 3-4) were blocked by mining task goal (priority 2). Jim couldn't eat while mining even when health dropped dangerously low.

**Fix:**
- Added `BlockHelper.tryEatIfLowHealth(companion, healthThreshold)` — checks HP%, tries `tryEatFood()`, falls back to pulling food from STORAGE
- Added `BlockHelper.hasFood(companion)` — checks inventory for DataComponents.FOOD items
- Added `BlockHelper.pullFoodFromStorage()` — pulls up to 16 food items from STORAGE
- Added health check to all 7 mining tasks: warns at 30% HP ("I'm getting low on health and don't have any food!")
- Each task has `foodWarningGiven` flag to prevent warning spam

### Files Modified
- `task/BlockHelper.java` — +482 lines (tool crafting, torch crafting, health check systems)
- `task/StripMineTask.java` — removed ~90 lines private torch/inventory helpers, added health/tool/inventory/torch warnings
- `task/mining/DigShaftTask.java` — removed ~67 lines private tryMidMineTorchCraft, added health/tool/torch checks
- `task/mining/BranchMineTask.java` — removed ~64 lines private tryMidMineTorchCraft, added health/tool/torch checks
- `task/mining/CreateHubTask.java` — removed ~54 lines private tryMidMineTorchCraft, uses shared method
- `task/MineOresTask.java` — added health check, food warning, emergency dig-out, handleFallingBlocks
- `task/MineBlocksTask.java` — added health check, food warning, handleFallingBlocks

### Technical Notes
- Fixed SLF4J format strings: `{:.0f}` (Python-style) → `{}` with `(int)` cast
- Removed unused imports from StripMineTask: SimpleContainer, ItemStack, ItemTags
- All changes built and deployed successfully

---

## Environment Reference

### Build & Deploy
```powershell
cd F:\MCAi
.\gradlew build -x test
Copy-Item "build\libs\mcai-0.1.0.jar" "C:\Users\travf\curseforge\minecraft\Instances\All the Mods 10 - ATM10\mods\mcai-0.1.0.jar" -Force
```

### Git
```powershell
$env:Path = "C:\Program Files\Git\bin;" + $env:Path
cd F:\MCAi
git add -A
git commit -m "description"
```

### Key Files
| File | Purpose |
|------|---------|
| `src/.../MCAi.java` | Mod entry point, LOGGER, registry init |
| `src/.../ai/AIService.java` | 3-tier AI routing, agent loop with dedup breaker, tool execution |
| `src/.../ai/CommandParser.java` | Local command parser, 100+ patterns, multi-part detection, fuzzy match |
| `src/.../ai/AiLogger.java` | Debug logger → `logs/mcai_debug.log`, categories, rotation at 10MB |
| `src/.../ai/CompanionMemory.java` | Persistent key-value memory system (facts, events, mine locations) |
| `src/.../ai/planner/RecipeResolver.java` | Recursive recipe resolution, tryManualRecipe, classifyRawMaterial |
| `src/.../ai/planner/CraftingPlan.java` | Ordered crafting steps, difficulty analysis, mob/mine/gather/farm assessment |
| `src/.../ai/tool/CraftItemTool.java` | Crafting orchestrator — resolve recipes, auto-fetch, BLOCKED report, gem hints |
| `src/.../ai/tool/CreateMineTool.java` | Create mine: shaft→hub→branches, mine memory resume, force new |
| `src/.../ai/tool/ListMinesTool.java` | List all remembered mine locations from memory |
| `src/.../task/BlockHelper.java` | Block ops, hazard system, tool/torch auto-craft, health check, dimension helpers |
| `src/.../task/StripMineTask.java` | Strip mine tunnel with ore scanning, fluid sealing, shared torch/tool/health |
| `src/.../task/TaskManager.java` | Async task queue, continuations, deterministic execution |
| `src/.../task/OreGuide.java` | Ore Y-level ranges, tool tiers, 27 resources (vanilla + Nether + modded via c:ores tags) |
| `src/.../task/mining/DigShaftTask.java` | 2×1 staircase shaft with torch/tool/health checks |
| `src/.../task/mining/CreateHubTask.java` | 7×5×4 hub room with furniture placement |
| `src/.../task/mining/BranchMineTask.java` | Systematic branch mining from hub with poke holes, auto-deposit |
| `src/.../task/mining/CreateMineTask.java` | Orchestrator: shaft→hub→branches, mine memory persistence |
| `src/.../task/mining/MineState.java` | Persistent mine state — shaft pos, hub, branch progress |
| `src/.../command/DiagnoseCommand.java` | `/mcai diagnose` — tests all vanilla items for resolution |
| `src/.../entity/CompanionEntity.java` | Jim entity, AI, inventory, equipment, idle chat, traversal, hazard safety |
| `src/.../entity/goal/CompanionOpenIronDoorGoal.java` | Iron door navigation via buttons/levers |
| `src/.../entity/goal/CompanionOpenFenceGateGoal.java` | Fence gate open/close with mob-safety delay |
| `src/.../entity/goal/CompanionOpenTrapdoorGoal.java` | Wooden trapdoor handling with void-safety check |
| `src/.../logistics/HomeBaseManager.java` | Auto-setup crafting infrastructure (table, furnace, cauldron) |
| `src/.../logistics/ItemRoutingHelper.java` | Route items to tagged containers with priority ordering |
| `src/.../network/ChatMessageHandler.java` | Server-side message routing: ! → CommandParser → AI |
| `src/.../client/CompanionChatScreen.java` | Chat GUI, auto-focus, URL clicking, Component rendering |
| `src/.../client/CompanionInventoryScreen.java` | Inventory GUI with Bug Report button |
| `src/.../client/CompanionHudOverlay.java` | HUD overlay — name, health bar, behavior mode, task status |
| `src/.../config/AiConfig.java` | Mod configuration (40+ settings, API keys, models, radii, etc.) |

### Architecture
- **Message Flow**: Player types in CompanionChatScreen → ChatMessagePacket → server ChatMessageHandler → multi-part? → AI : CommandParser → fallback AI
- **AI Flow**: AIService → cloud API → tool calls → execute tools → dedup check → loop until text response or 3x identical call
- **Task Flow**: Tool returns [ASYNC_TASK] → TaskManager queues → tick-based execution → [TASK_COMPLETE]/[TASK_FAILED] → continuation with plan parameter
- **Fallback Chain**: Cloud primary (Groq) → Cloud fallback (OpenRouter) → Local Ollama (llama3.1)
- **Recipe Resolution**: resolve() → resolveRecursive() → [raw material | tryManualRecipe | Phase 1-4 recipe lookup | fallback raw]
- **Hazard Safety**: Pathfinding malus (lava/fire/damage/powder snow -1.0) + hazardCheck() every 10 ticks + `getBlockHazard()` (11 types) + `sealHazardousFloor()` + `clearTunnelHazards()` + `isSafeToMine()` (lava+water) + `emergencyDigOut()` on stuck
- **Mining Safety**: Auto-craft pickaxe on break (4 tiers) + shared torch crafting with warning + in-task health check/auto-eat from inventory or STORAGE + inventory full auto-deposit + falling block handling + fluid sealing
- **Crafting Chain**: craft_item → resolve → fetch from containers → autoResolve intermediates → autoCraftPlan → RecipeResolver tree → CraftingPlan → async tasks (chop/mine/smelt) → continuation → final craft

### Diagnostic Results
| Run | Resolved | Total | Rate | Unknowns |
|-----|----------|-------|------|----------|
| 1st | 985 | 1151 | 85.6% | 110 |
| 2nd | 1094 | 1151 | 95.0% | 48 |
| 3rd | Pending — need to run after shulker/netherite/carpet fixes | | ~97%+ expected | ~20 |


---

## Session 18 — Expanded Ore Guide, Mine Resume Fix, Naming Guide (2026-02-14)

### Branch Mine Resume Fix
- **Root cause**: Staircase shaft moves 2 blocks forward per 1 Y-level descended, but `resumeExistingMine()` used `depth * 1` — placed hub center and all branches in solid rock
- **Fix**: Changed to `entrance.relative(direction, depth * 2)` in `CreateMineTool.resumeExistingMine()`
- **Hub center persistence** (v2 memory format): `saveMineToMemory()` now appends `|hubX,hubY,hubZ`, `parseMineMemory()` reads it, `CreateHubTask` updates mine memory after hub is built
- **Better logging**: Branch start/hub positions logged in stuck messages for easier debugging

### Expanded OreGuide — 27 Resources
Expanded `OreGuide.java` from 8 vanilla ores to 27 resources using NeoForge common tags (`c:ores/*`):

| Category | Resources | Tags |
|----------|-----------|------|
| **Vanilla Overworld** (8) | Coal, Copper, Iron, Lapis, Gold, Redstone, Diamond, Emerald | `minecraft:*_ores` |
| **Vanilla Nether** (3) | Nether Quartz, Nether Gold, Ancient Debris | `c:ores/quartz`, `c:ores/gold`, `c:ores/netherite_scrap` |
| **Mekanism** (5) | Osmium, Tin, Lead, Uranium, Fluorite | `c:ores/osmium`, `tin`, `lead`, `uranium`, `fluorite` |
| **Thermal Series** (4) | Silver, Nickel, Sulfur, Apatite | `c:ores/silver`, `nickel`, `sulfur`, `apatite` |
| **Create** (1) | Zinc | `c:ores/zinc` |
| **AE2** (1) | Certus Quartz | `c:ores/certus_quartz` |
| **IE** (1) | Aluminum | `c:ores/aluminum` |
| **Gems** (3) | Ruby, Sapphire, Peridot | `c:ores/ruby`, `sapphire`, `peridot` |
| **Misc** (2) | Iridium, Platinum | `c:ores/iridium`, `platinum` |

New features in OreGuide:
- `commonOreTag()` helper for creating NeoForge common tags
- `modded` and `nether` flags on each ore entry
- `matchAlias()` for alternate names (netherite→ancient debris, quartz→nether quartz, etc.)
- `vanillaOres()`, `moddedOres()`, `overworldOres()` filtered lists
- `allOreNames()` for dynamic error messages
- Expanded `findByName()` with `_dust`, `_gem`, `_crystal` stripping
- `getMiningGuide()` organized by category (Vanilla / Nether / Modded)

### Patchouli Guide Updates
- **Mining Basics**: Split ore Y-levels into "Vanilla" and "Modded" pages with all 27 resources listed
- **Create Mine**: New "Resource-Based Mines" first page explaining `"create a [resource] mine"` syntax with examples; added auto-craft tool and auto-eat to safety features
- **Naming**: New entry `ai_chat/naming.json` explaining how to rename companion via chat, persistence, display

### Error Message Updates
- `CreateMineTool` and `StripMineTool` now use dynamic `OreGuide.allOreNames()` for "Unknown ore" error messages
- Tool descriptions updated to mention modded ore support and resource-based mine examples

### Files Changed
| File | Change |
|------|--------|
| `task/OreGuide.java` | Expanded from 8→27 ores, NeoForge common tags, alias matching, filtered lists |
| `ai/tool/CreateMineTool.java` | Mine resume fix (depth*2), v2 hub center memory, modded ore description, dynamic error msg |
| `task/mining/CreateMineTask.java` | v2 memory format (hub center), backward-compatible parser |
| `task/mining/CreateHubTask.java` | `updateMineMemoryWithHub()` saves hub center after construction |
| `task/mining/BranchMineTask.java` | Position logging in stuck warnings for hub/branch navigation |
| `ai/tool/StripMineTool.java` | Dynamic error message for unknown ores |
| `patchouli: mining_basics.json` | Added modded ore Y-levels page, Nether resources |
| `patchouli: create_mine.json` | Resource-based mines page, auto-craft/auto-eat in safety |
| `patchouli: naming.json` | NEW — companion naming guide (2 pages) |
| `README.md` | Updated mining section (resource mines, 27 resources, auto-craft, auto-eat), added naming feature |

---

## Session 19 — Hub Furniture Auto-Craft & Layout Fix (2026-02-15)

**Commit**: `3b6c6dc` — "Fix hub furniture: auto-craft items + back-wall row layout"

### Problem
Hub furniture (chests, furnace, crafting table) was never actually placed. `BlockHelper.placeBlock()` requires the block item to exist in the companion's inventory, but nothing ever crafted these items — placement silently failed every time.

### Fix: Auto-Craft Hub Furniture
Added `BlockHelper.tryAutoCraftHubFurniture()` that auto-crafts all hub furniture from inventory materials before placement:

| Item | Recipe | Materials |
|------|--------|-----------|
| Crafting Table | 4 planks → 1 | 4 planks |
| Furnace | 8 cobblestone → 1 | 8 cobblestone |
| 2 Chests | 8 planks each → 1 | 16 planks |
| **Total** | | **20 planks + 8 cobblestone** |

- `ensurePlanks()` helper converts logs → planks as needed
- Pulls materials from tagged STORAGE containers if available
- Logs warnings when materials are insufficient

### Fix: Back-Wall Row Layout
Rearranged furniture from scattered left/right walls to a single row along the back wall:
- All 4 items at the same Y level, side by side
- Layout (left to right): **Chest, Chest, Furnace, Crafting Table**
- Two adjacent chests form a double chest

### Logging & Feedback
- Per-item success/failure logging with position info
- Chat message reports how many items were placed: "Placed X/4 hub furniture items"

### Files Changed
| File | Change |
|------|--------|
| `task/BlockHelper.java` | Added `tryAutoCraftHubFurniture()`, `ensurePlanks()` (+73 lines) |
| `task/mining/CreateHubTask.java` | New layout (back-wall row), auto-craft call, per-item logging |
| `README.md` | Added hub auto-furnishing to mining section |
| `patchouli: create_mine.json` | Updated Phase 2 Hub Room with auto-craft + layout details |
| `TEST_CHECKLIST.md` | Added 8 hub furniture test cases (188→196 total) |
| `SESSION_LOG.md` | Added Session 19 |

*Last updated: 2026-02-15 — Session 19*
