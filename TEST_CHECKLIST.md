# MCAi Feature Test Checklist

Comprehensive test list covering all features across 16 categories.
Mark each test ✅ (pass), ❌ (fail), or ⏭️ (skipped) with notes.

---

## 1. Summoning & Lifecycle (10 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 1 | Right-click Soul Crystal to summon Jim | | |
| 2 | Jim spawns at player's position | | |
| 3 | Jim has correct display name ("Jim" or custom) | | |
| 4 | Second summon attempt prevented (duplicate check) | | |
| 5 | Sneak + hit to dismiss Jim | | |
| 6 | Jim drops inventory on death | | |
| 7 | Jim respawns when Soul Crystal used again after death | | |
| 8 | Jim persists across world save/reload | | |
| 9 | Jim's inventory persists across save/reload | | |
| 10 | Jim's behavior mode persists across save/reload | | |

---

## 2. Interaction & UI (12 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 11 | Right-click Jim → opens inventory screen | | |
| 12 | Sneak + right-click Jim → opens chat screen | | |
| 13 | Inventory shows 27 general + 4 armor + offhand slots | | |
| 14 | Can move items between player and Jim inventory | | |
| 15 | Chat screen auto-focuses text input (no mouse click needed) | | |
| 16 | Chat screen shows conversation history | | |
| 17 | HUD overlay shows name, health bar, behavior mode | | |
| 18 | HUD overlay shows current task status | | |
| 19 | H key opens HUD edit screen (drag to reposition) | | |
| 20 | HUD position persists across sessions | | |
| 21 | Bug Report button visible in inventory screen | | |
| 22 | Chat URLs are clickable (blue, underlined, opens browser) | | |

---

## 3. Behavior Modes (8 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 23 | "follow me" → Jim follows player | | |
| 24 | "stay" → Jim stops and stands still | | |
| 25 | "wander" / "auto" → Jim enters AUTO mode | | |
| 26 | Follow mode: Jim pathfinds to player | | |
| 27 | Follow mode: Jim teleports when >64 blocks away (whistle) | | |
| 28 | G key whistle: Jim comes to player | | |
| 29 | Stay mode: Jim doesn't move when player walks away | | |
| 30 | Guard mode: Jim patrols around guard point | | |

---

## 4. Combat (8 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 31 | Jim attacks hostile mobs automatically | | |
| 32 | Jim fights back when hit by mobs | | |
| 33 | Jim equips best weapon from inventory | | |
| 34 | Jim equips armor from inventory | | |
| 35 | "kill zombie" → Jim hunts zombies (KillMobTask) | | |
| 36 | "guard here" → Jim enters GUARD mode at position | | |
| 37 | Jim takes damage (not invulnerable) | | |
| 38 | Jim renders with equipped armor visually | | |

---

## 5. Food & Health (10 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 39 | Jim eats food from inventory when health < 80% (goal) | | |
| 40 | Jim heals by nutrition value of food eaten | | |
| 41 | Jim has 40-tick eat cooldown (no spam eating) | | |
| 42 | Jim hunts food animals when hungry (HuntFoodGoal) | | |
| 43 | Jim fetches food from nearby containers (FetchFoodGoal) | | |
| 44 | Jim cooks raw food in furnaces (CookFoodGoal) | | |
| 45 | Mining health check: auto-eat at < 50% HP during tasks | | |
| 46 | Mining health check: pulls food from STORAGE if inventory empty | | |
| 47 | Health warning at 30% HP: "I'm getting low on health..." | | |
| 48 | Food warning only appears once (flag prevents spam) | | |

---

## 6. Item Pickup & Logistics (12 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 49 | Jim picks up dropped items nearby (PickupItemGoal) | | |
| 50 | Jim auto-equips picked up armor | | |
| 51 | Logistics Wand: tag container as INPUT | | |
| 52 | Logistics Wand: tag container as OUTPUT | | |
| 53 | Logistics Wand: tag container as STORAGE | | |
| 54 | Logistics Wand: set HOME_AREA corners | | |
| 55 | Logistics Wand: cycle modes with sneak+right-click | | |
| 56 | Tagged block outlines render in correct colors | | |
| 57 | Home area boundary renders as outline | | |
| 58 | Jim routes items to tagged containers (LogisticsGoal) | | |
| 59 | Jim doesn't mine inside home area (protection) | | |
| 60 | Wand HUD shows current mode | | |

---

## 7. Chat & AI (16 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 61 | Type message → Jim responds via AI | | |
| 62 | "!" prefix commands bypass AI (CommandParser) | | |
| 63 | Fuzzy matching handles typos ("folow me" → follow) | | |
| 64 | Multi-part commands route to AI ("craft a chest and place it") | | |
| 65 | Greeting recognition ("hello", "hey jim") | | |
| 66 | Status query ("how are you", "what are you doing") | | |
| 67 | Web search triggered by question prefixes | | |
| 68 | AI falls back: cloud primary → cloud fallback → Ollama | | |
| 69 | Agent loop dedup: stops after 3 identical tool calls | | |
| 70 | Proactive idle chat (occasional unprompted messages) | | |
| 71 | "mute" → disables proactive chat | | |
| 72 | "unmute" → re-enables proactive chat | | |
| 73 | Jim personality: sarcastic, loyal, enthusiastic responses | | |
| 74 | "rename jim to X" → changes display name | | |
| 75 | "remember X" → stores fact in memory | | |
| 76 | "what do you remember" → recalls stored facts | | |

---

## 8. Crafting (12 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 77 | "craft iron pickaxe" → resolves recipe, crafts item | | |
| 78 | Recipe resolver handles recursive dependencies | | |
| 79 | Auto-fetch materials from tagged STORAGE containers | | |
| 80 | Difficulty warnings: IMPOSSIBLE, DANGEROUS, WARNING | | |
| 81 | BLOCKED report with materials list when craft fails | | |
| 82 | Gem/mineral hints: suggests `mine_ores` for diamonds | | |
| 83 | Manual recipes work (shulker box, netherite, carpet) | | |
| 84 | Smelting works via SmeltItemsTool | | |
| 85 | Jim locates crafting table for crafting | | |
| 86 | Jim locates furnace for smelting | | |
| 87 | HomeBaseManager auto-places crafting infrastructure | | |
| 88 | `!craft` command works via CommandParser | | |

---

## 9. Strip Mining (14 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 89 | "mine iron" → starts strip mine at optimal Y | | |
| 90 | Tunnel digs 1×2 in specified direction | | |
| 91 | Jim walks out of home area before mining | | |
| 92 | Jim digs staircase down to target Y-level | | |
| 93 | Ores in tunnel walls detected and queued for mining | | |
| 94 | All ore types mined (not just target — bonus resources) | | |
| 95 | Torches placed every 8 blocks | | |
| 96 | Auto-craft torches from coal + sticks (shared method) | | |
| 97 | Torch exhaustion warning when out of materials | | |
| 98 | Lava detected → attempt seal with cobblestone | | |
| 99 | Water source detected → attempt seal with cobblestone | | |
| 100 | Hazardous floor sealed (magma, fire, lava, air) | | |
| 101 | Tunnel hazards cleared (cobwebs, fire, berry bushes) | | |
| 102 | Inventory full → auto-deposit to STORAGE, then continue | | |

---

## 10. Create Mine System (16 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 103 | "create mine diamonds" → full mine workflow | | |
| 104 | DigShaftTask: walkable 2×1 staircase to target Y | | |
| 105 | Shaft: safe floor under each step (cobblestone fill) | | |
| 106 | Shaft: torches every 8 blocks | | |
| 107 | Shaft: ore scanning while descending | | |
| 108 | CreateHubTask: 7×5×4 room cleared | | |
| 109 | Hub: chests, furnace, crafting table placed | | |
| 109a | Hub: auto-crafts chests from planks in inventory | | |
| 109b | Hub: auto-crafts furnace from 8 cobblestone | | |
| 109c | Hub: auto-crafts crafting table from 4 planks | | |
| 109d | Hub: converts logs to planks when planks insufficient | | |
| 109e | Hub: furniture placed in back-wall row (same Y level) | | |
| 109f | Hub: two adjacent chests form a double chest | | |
| 109g | Hub: chat reports X/4 furniture items placed | | |
| 109h | Hub: handles missing materials gracefully | | |
| 110 | Hub: 4 torches placed on walls | | |
| 111 | BranchMineTask: branches extend from corridor | | |
| 112 | Branch: poke holes every 4 blocks for extra coverage | | |
| 113 | Branch: ore scanning in walls/ceiling/floor | | |
| 114 | Branch: inventory at 80% → deposit in hub chests | | |
| 115 | Mine memory: existing mine resumed on re-command | | |
| 116 | Mine memory: "create new mine" forces fresh mine | | |
| 117 | "list mines" / "where are my mines" → shows all mines | | |
| 118 | Torch pre-fetch from STORAGE before mine starts | | |

---

## 11. Tool & Resource Management (12 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 119 | Auto-craft pickaxe when tool breaks (Diamond→Iron→Stone→Wood) | | |
| 120 | Pull crafting materials from tagged STORAGE | | |
| 121 | Craft prerequisite sticks from planks | | |
| 122 | Craft prerequisite planks from logs | | |
| 123 | Tool break → "Crafted a new pickaxe! Continuing." message | | |
| 124 | No pickaxe and can't craft → stops with warning | | |
| 125 | Jim equips best tool for block type automatically | | |
| 126 | Falling block handling: gravel/sand mined after ore | | |
| 127 | Emergency dig-out when trapped by falling blocks | | |
| 128 | `tryAutoCraftTool()` works for AXE, SHOVEL, HOE, SWORD too | | |
| 129 | Pull torch materials from STORAGE (coal, charcoal, sticks) | | |
| 130 | Shared torch crafting: logs→planks→sticks→torches chain | | |

---

## 12. Hazard & Safety (14 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 131 | Pathfinding avoids lava (malus -1.0) | | |
| 132 | Pathfinding avoids fire (malus -1.0) | | |
| 133 | Pathfinding avoids powder snow (malus -1.0) | | |
| 134 | Pathfinding discourages leaves (malus 4.0) | | |
| 135 | Pathfinding discourages water (malus 4.0) | | |
| 136 | `isSafeToMine()` blocks mining next to lava | | |
| 137 | `isSafeToMine()` blocks mining next to water source | | |
| 138 | `sealHazardousFloor()` covers: air, lava, magma, fire, dripstone | | |
| 139 | `clearTunnelHazards()` breaks cobwebs, fire, berry bushes in tunnel | | |
| 140 | `getBlockHazard()` detects all 11 hazard types | | |
| 141 | Water stuck rescue: teleport to owner after N ticks in water | | |
| 142 | Void safety: hazard check teleports companion before void death | | |
| 143 | Suffocation safety: breaks blocks when suffocating | | |
| 144 | Lava flee: companion moves away from lava when standing in it | | |

---

## 13. Traversal (10 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 145 | Jim opens wooden doors while pathfinding | | |
| 146 | Jim closes wooden doors behind (after delay) | | |
| 147 | Jim opens iron doors via adjacent buttons/levers | | |
| 148 | Jim opens fence gates while pathfinding | | |
| 149 | Jim closes fence gates behind (mob safety delay) | | |
| 150 | Jim opens wooden trapdoors | | |
| 151 | Trapdoor void safety: won't open over void/deep drops | | |
| 152 | Step height allows climbing 1-block steps | | |
| 153 | Jim can traverse water (pathfinding malus allows crossing) | | |
| 154 | Jim navigates ladders | | |

---

## 14. Other Tasks (12 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 155 | "chop trees" → ChopTreesTask mines logs + leaves | | |
| 156 | "farm wheat" → FarmAreaTask hoes/plants/harvests | | |
| 157 | "gather cobblestone" → GatherBlocksTask collects blocks | | |
| 158 | "build a wall" → BuildTask places blocks in pattern | | |
| 159 | "go fishing" → FishingTask with random catch times | | |
| 160 | "trade with villager" → VillagerTradeTool | | |
| 161 | "mine ores" → MineOresTask with progressive radius expansion | | |
| 162 | "mine area" → MineBlocksTask clears rectangular volume | | |
| 163 | "deliver items to X" → DeliverItemsTask | | |
| 164 | "scan surroundings" → ScanSurroundingsTool report | | |
| 165 | "what's in that chest" → ScanContainersTool | | |
| 166 | Task cancel: "stop" / "cancel" halts current task | | |

---

## 15. AI Tools (12 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 167 | GetInventoryTool: lists companion/player inventory | | |
| 168 | GetRecipeTool: looks up crafting recipes | | |
| 169 | GetLookedAtBlockTool: identifies block player looks at | | |
| 170 | BookmarkLocationTool: save/recall named locations | | |
| 171 | MemoryTool: remember/recall/forget facts | | |
| 172 | TransferItemsTool: move items between inventories | | |
| 173 | InteractContainerTool: insert/extract from containers | | |
| 174 | FindAndFetchItemTool: find item in containers and fetch | | |
| 175 | EmoteTool: particles and sounds for emotes | | |
| 176 | ListInstalledModsTool: shows all loaded mods | | |
| 177 | ExecuteCommandTool: runs commands with security | | |
| 178 | TaskStatusTool: get/cancel current tasks | | |

---

## 16. Configuration & Infrastructure (10 tests)

| # | Test | Status | Notes |
|---|------|--------|-------|
| 179 | `mcai-common.toml` generates on first run | | |
| 180 | Cloud API key configurable (Groq/OpenRouter/etc.) | | |
| 181 | Fallback AI chain works when primary is down | | |
| 182 | Debug logging to `logs/mcai_debug.log` | | |
| 183 | `/mcai diagnose` command runs recipe resolution test | | |
| 184 | Chunk loading around companion during active tasks | | |
| 185 | Patchouli guide book shows in creative tab / EMI | | |
| 186 | Guide book recipe: book + soul crystal = guide | | |
| 187 | Voice input: V key push-to-talk with Whisper | | |
| 188 | Custom tech textures for Soul Crystal, Guide Book, Wand | | |

---

## Summary

| Category | Tests | Pass | Fail | Skip |
|----------|-------|------|------|------|
| 1. Summoning & Lifecycle | 10 | | | |
| 2. Interaction & UI | 12 | | | |
| 3. Behavior Modes | 8 | | | |
| 4. Combat | 8 | | | |
| 5. Food & Health | 10 | | | |
| 6. Item Pickup & Logistics | 12 | | | |
| 7. Chat & AI | 16 | | | |
| 8. Crafting | 12 | | | |
| 9. Strip Mining | 14 | | | |
| 10. Create Mine System | 16 | | | |
| 11. Tool & Resource Management | 12 | | | |
| 12. Hazard & Safety | 14 | | | |
| 13. Traversal | 10 | | | |
| 14. Other Tasks | 12 | | | |
| 15. AI Tools | 12 | | | |
| 16. Configuration & Infrastructure | 10 | | | |
| **TOTAL** | **196** | | | |

---

*Created: 2026-01-20 — Session 17*
*Updated: 2026-02-15 — Session 19 (hub furniture tests)*
*Covers all features through Session 19*
