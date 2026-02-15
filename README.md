# MCAi — AI Companion for Minecraft

An AI-powered companion mod for NeoForge 1.21.1 that can mine, craft, build, fight, sort items, and hold conversations — all driven by natural language.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.77-orange)
![License](https://img.shields.io/badge/License-MIT-blue)

## Overview

MCAi adds an AI companion entity to Minecraft that understands natural language commands and can perform complex multi-step tasks autonomously. Ask it to "craft an iron pickaxe" and it will figure out the entire dependency chain — chopping trees, crafting planks, making tools, mining stone, mining iron, smelting, and crafting the final item.

The companion uses a cloud AI backend (Groq / OpenRouter) with Ollama as a local fallback, giving it the ability to reason about tasks, remember past conversations, and make plans.

## Features

### AI Chat & Memory
- Natural language conversation via in-game chat GUI
- Voice input via Whisper speech-to-text
- Persistent companion memory across sessions
- Emote system with animations
- Web search and webpage fetching for real-world info
- **Proactive idle chat** — companion speaks up with suggestions after being idle
- **Mute/unmute** — "shut up" or "stop talking" silences idle chat; "talk again" re-enables
- **Multi-part command understanding** — "craft a chest and place it next to the furnace" routes to AI for decomposition into chained tool calls
- **GitHub bug report button** in inventory screen

### Autonomous Crafting
- Full dependency tree resolution — resolves recipes recursively
- Auto-crafts intermediate items (planks → sticks → tools)
- Auto-gathers missing raw materials (chop, mine, gather)
- Auto-crafts required tools (need stone pickaxe? crafts wooden first)
- Auto-smelts ores with fuel management
- Auto-places furnaces and crafting tables as needed
- Fetches materials from nearby tagged storage chests
- **Smart material hints** — diamonds/emeralds suggest mining, not gathering
- **Agent loop safety** — detects repeated identical tool calls and stops retrying
- **Crafting difficulty warnings** — color-coded alerts for impossible/dangerous ingredients

### Mining
- **Ore scanning** — finds specific ores in range
- **Strip mining** — staircase descent to target Y-level, then tunnel with ore detection
- **Permanent mines** — Create hub with branching tunnels, resumable across sessions
- **Area mining** — clears a defined region

### Logistics & Home Area
- **Logistics Wand** — tag containers as Input/Output/Storage
- **Home Area** — define a protected bounding box, companion won't break blocks inside it
- **Container protection** — tagged containers and functional blocks are never broken
- **Auto-sorting** — companion moves items between tagged containers
- Wand modes: Input (blue), Output (orange), Storage (green), Home Area (cyan), Clear Home (red)

### Combat & Survival
- Auto-equips best armor and weapons
- Guard mode — patrols and defends an area
- Hunt specific mob types
- Death recovery — inventory preserved, resummon via Soul Crystal after 60s cooldown

### Traversal & Navigation
- **Step height 1.0** — walks up full blocks without jumping
- **Door handling** — opens/closes wooden doors; presses buttons for iron doors
- **Fence gates** — opens, passes through, closes behind (prevents mob entry)
- **Trapdoors** — opens wooden trapdoors with void-safety check
- **Water traversal** — swims freely, never drowns
- **Ladders/vines/scaffolding** — climbs naturally
- **Display name sync** — health bar mods show correct name (not "MCAi")

### Farming & Gathering
- Farm areas with auto-replant
- Chop trees with replant
- Gather specific blocks (flowers, sand, mushrooms, stone)
- Staircase dig-down for underground blocks (with lava safety)
- Fishing at nearby water

### Building & Delivery
- Build simple structures from plans
- Deliver items to specific locations
- Villager trading

### Commands & Controls
- Quick commands via `!` prefix (bypass AI)
- Configurable keybinds for chat, follow, stay
- Location bookmarks
- HUD overlay showing companion status, health, task progress
- Draggable HUD position

## Items

| Item | Recipe | Usage |
|------|--------|-------|
| **Soul Crystal** | 2 Diamond + 2 Gold + 1 Ender Pearl | Summon/dismiss companion, resummon after death |
| **Logistics Wand** | 1 Ender Pearl + 1 Gold Nugget + 1 Iron | Tag containers, set home area, cycle modes with Shift+Scroll |

## AI Tools (34)

The companion has 34 tools it can call autonomously based on conversation.
Complex multi-part commands ("mine iron and then craft a pickaxe") are automatically decomposed by the AI into sequential tool calls.

| Category | Tools |
|----------|-------|
| **Info** | `web_search`, `web_fetch`, `get_inventory`, `scan_surroundings`, `get_recipe`, `get_looked_at_block`, `scan_containers`, `list_installed_mods` |
| **Items** | `interact_container`, `find_and_fetch_item`, `transfer_items`, `deliver_items` |
| **Crafting** | `craft_item`, `smelt_items` |
| **Gathering** | `chop_trees`, `mine_ores`, `mine_area`, `gather_blocks`, `dig_down`, `farm_area`, `fishing` |
| **Mining** | `strip_mine`, `create_mine` |
| **Combat** | `guard_area`, `kill_mob` |
| **Building** | `build_structure`, `set_block` |
| **Social** | `emote`, `rename_companion`, `villager_trade` |
| **Utility** | `bookmark_location`, `execute_command`, `memory`, `task_status` |

## AI Backend

MCAi uses a fallback chain for AI requests:

1. **Groq** (primary) — fast cloud inference, free tier
2. **OpenRouter** (fallback) — secondary cloud provider
3. **Ollama** (local fallback) — runs on your GPU, auto-starts/stops with the game

The agent loop includes safety mechanisms:
- **Deduplication breaker** — detects 3+ identical tool calls and forces a stop
- **[CANNOT_CRAFT] directive** — prevents infinite retry loops when materials are missing
- **Async task marker** — queued tasks (mining, smelting) get a single status response

Configure API keys in the mod config (`mcai-common.toml`).

## Block Protection System

The companion has a multi-layer protection system to prevent griefing your base:

1. **Tagged blocks** — containers marked as Input/Output/Storage are never broken
2. **Home area containers** — any container inside the home area is protected
3. **Home area functional blocks** — crafting tables, furnaces, anvils, enchanting tables, brewing stands inside the home area are protected
4. **Scan exclusion** — resource scanning (blocks, ores) skips positions inside the home area

## Level System

The companion gains XP from completing tasks and levels up:
- Mining, crafting, chopping, gathering, combat all grant XP
- Higher levels unlock better capabilities
- Level persists through dismiss/resummon

## Configuration

All settings are in `mcai-common.toml`:
- AI provider API keys and model selection
- Companion behavior (follow distance, home range, logistics range)
- Tool permissions
- Ollama auto-start settings

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.77+
- At least one AI backend configured (Groq API key recommended)

## Building from Source

```bash
git clone https://github.com/apocscode/mcai.git
cd mcai
./gradlew build
```

The built jar will be at `build/libs/mcai-0.1.0.jar`.

## License

MIT License — see [LICENSE](LICENSE) for details.

## Author

**Apocscode**
