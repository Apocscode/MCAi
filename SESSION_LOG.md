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

*Last updated: 2026-02-14 — Session 7*
*Rule: Update this log with every JAR deployment.*
