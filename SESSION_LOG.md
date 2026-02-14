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

*Last updated: 2026-02-14*
