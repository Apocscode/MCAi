# MCAi — AI Backend Guide

This guide explains how MCAi's AI system works, what providers are supported, how to configure them, and what to expect from free vs paid models.

## How the AI Works

MCAi's companion has two layers:

1. **AI Brain** — A large language model (LLM) that reads your chat messages, decides what to do, and picks which tools to call with what parameters. This is the "thinking" layer.
2. **Java Execution** — 35 hardcoded tools that perform the actual actions (mining, crafting, pathfinding, block placement, combat, etc.). This is the "doing" layer.

The AI never writes code or directly controls the companion's movement. It acts as a **dispatcher** — interpreting your natural language, choosing the right tool, and generating a brief chat response. All task execution is deterministic Java code.

### Agent Loop

When you send a message:

```
You: "mine some iron and craft a pickaxe"
  ↓
AI receives message + system prompt + tool definitions
  ↓
AI decides: call mine_ores(ore="iron")
  ↓
Tool executes → returns result to AI
  ↓
AI decides: call craft_item(item="iron_pickaxe", plan="...")
  ↓
Tool executes → returns [ASYNC_TASK]
  ↓
AI responds: "On it! Mining iron and then I'll craft you a pickaxe."
```

The AI can chain up to **10 tool calls** per message (configurable). A deduplication breaker stops it if it makes 3+ identical calls in a row.

### Continuation System

Long tasks (mining, smelting, multi-step crafting) run asynchronously. When a task completes, the system feeds the result back to the AI with a continuation plan, and the AI calls the next tool in the chain. This allows fully autonomous multi-step operations like "craft an iron pickaxe from nothing."

## Provider Architecture

MCAi uses a **three-tier fallback chain**:

```
Primary Cloud → Fallback Cloud → Local Ollama
```

1. **Primary Cloud** — Your main AI provider (default: Groq)
2. **Fallback Cloud** — A second cloud provider, activated when the primary returns HTTP 429 (rate limited)
3. **Ollama** — Local LLM running on your GPU, used when both cloud providers fail

All cloud providers use the **OpenAI-compatible chat completions API** — the industry standard format. This means any provider that supports this format works with MCAi by changing just 3 config values.

## Supported Providers

### Free Providers

| Provider | API URL | Recommended Model | Free Tier Limits |
|----------|---------|-------------------|-----------------|
| **Groq** | `https://api.groq.com/openai/v1/chat/completions` | `meta-llama/llama-4-scout-17b-16e-instruct` | 30 req/min, 14,400 req/day |
| **OpenRouter** | `https://openrouter.ai/api/v1/chat/completions` | `meta-llama/llama-3.3-70b-instruct:free` | Varies by model, many free options |
| **Cerebras** | `https://api.cerebras.ai/v1/chat/completions` | `llama3.1-8b` | Free, very fast inference |
| **SambaNova** | `https://api.sambanova.ai/v1/chat/completions` | `Meta-Llama-3.1-70B-Instruct` | Free, generous limits |

### Paid Providers

| Provider | API URL | Recommended Model | Approx. Cost (heavy play) |
|----------|---------|-------------------|--------------------------|
| **OpenAI** | `https://api.openai.com/v1/chat/completions` | `gpt-4o-mini` | ~$0.05–0.15/day |
| **OpenAI** | `https://api.openai.com/v1/chat/completions` | `gpt-4o` | ~$0.50–2.00/day |
| **OpenRouter** | `https://openrouter.ai/api/v1/chat/completions` | `anthropic/claude-3.5-sonnet` | ~$0.75–2.50/day |
| **Together** | `https://api.together.xyz/v1/chat/completions` | `meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo` | ~$0.10–0.30/day |
| **Groq** (paid) | `https://api.groq.com/openai/v1/chat/completions` | `llama-3.3-70b-instruct` | ~$0.05–0.15/day |

### Local (Ollama)

| Setup | Model | Requirements |
|-------|-------|-------------|
| **Ollama** | `llama3.1` (default) | 8GB+ VRAM recommended |

Ollama runs entirely on your machine — no API key needed, no internet required. MCAi auto-starts and auto-stops Ollama with the game. It's the ultimate fallback but slower and less capable than cloud models.

## Free vs Paid — What's the Difference?

### What the AI model controls:
- **Tool selection** — picking the right tool for your command
- **Parameter extraction** — parsing "mine some iron" → `mine_ores(ore="iron")`
- **Multi-step reasoning** — breaking "craft iron armor" into a chain of gather/smelt/craft calls
- **Continuation following** — reading task results and calling the next correct tool
- **Chat personality** — tone, humor, helpfulness of responses

### What the AI model does NOT control:
- **Task execution speed** — hardcoded Java, same on any model
- **Pathfinding quality** — deterministic algorithms, model-independent
- **Block placement/mining** — Java task code, not AI-dependent
- **Tool capabilities** — tools do what they do regardless of which model calls them

### Comparison

| Aspect | Free (Llama on Groq) | Paid (GPT-4o-mini) | Premium (GPT-4o / Claude) |
|--------|----------------------|---------------------|---------------------------|
| Tool call accuracy | ~85–90% | ~95–98% | ~98–99% |
| Multi-part commands | Sometimes picks wrong tool | Reliably decomposes | Excellent decomposition |
| Continuation chains | Occasionally misreads next step | Very reliable | Near-perfect |
| Rate limits | 30 req/min (triggers fallback) | Effectively unlimited | Effectively unlimited |
| Chat personality | Good but formulaic | Natural and contextual | Excellent personality |
| Cost | $0 | ~$0.05–0.15/day | ~$0.50–2.50/day |

### Recommendation

**For most players:** The default free Groq setup works well. The system prompt is heavily optimized to compensate for smaller models.

**For the best experience:** `gpt-4o-mini` via OpenAI is the sweet spot — dramatically better tool calling for pennies per day of play.

**For maximum quality:** `gpt-4o` or `anthropic/claude-3.5-sonnet` (via OpenRouter) give the best reasoning, but cost more.

## Configuration

All AI settings are in the mod config file:

```
config/mcai-common.toml
```

You can edit this file directly, or change settings in-game via Mod Menu / config screen.

### Quick Start — Using a Different Provider

**Step 1:** Get an API key from your chosen provider:
- Groq: https://console.groq.com (free)
- OpenAI: https://platform.openai.com (paid)
- OpenRouter: https://openrouter.ai (free + paid models)
- Together: https://together.ai ($5 free credit)

**Step 2:** Edit `config/mcai-common.toml`:

```toml
[ai.cloud]
    # Your API key
    cloudApiKey = "your-api-key-here"
    
    # The model to use (see provider tables above)
    cloudModel = "gpt-4o-mini"
    
    # The API endpoint URL
    cloudUrl = "https://api.openai.com/v1/chat/completions"
```

**Step 3:** (Optional) Set up a fallback provider for seamless failover:

```toml
[ai.cloud_fallback]
    fallbackApiKey = "your-fallback-key"
    fallbackModel = "meta-llama/llama-3.3-70b-instruct:free"
    fallbackUrl = "https://openrouter.ai/api/v1/chat/completions"
```

**Step 4:** Restart Minecraft / reload the world. Changes take effect immediately on world load.

### Example Configurations

#### Free Setup (Default)
```toml
cloudApiKey = "gsk_your_groq_key"
cloudModel = "meta-llama/llama-4-scout-17b-16e-instruct"
cloudUrl = "https://api.groq.com/openai/v1/chat/completions"

fallbackApiKey = "sk-or-your_openrouter_key"
fallbackModel = "meta-llama/llama-3.3-70b-instruct:free"
fallbackUrl = "https://openrouter.ai/api/v1/chat/completions"
```

#### Budget Paid Setup (~$0.10/day)
```toml
cloudApiKey = "sk-your_openai_key"
cloudModel = "gpt-4o-mini"
cloudUrl = "https://api.openai.com/v1/chat/completions"

fallbackApiKey = "gsk_your_groq_key"
fallbackModel = "meta-llama/llama-4-scout-17b-16e-instruct"
fallbackUrl = "https://api.groq.com/openai/v1/chat/completions"
```

#### Premium Setup (~$1–2/day)
```toml
cloudApiKey = "sk-or-your_openrouter_key"
cloudModel = "anthropic/claude-3.5-sonnet"
cloudUrl = "https://openrouter.ai/api/v1/chat/completions"

fallbackApiKey = "sk-your_openai_key"
fallbackModel = "gpt-4o-mini"
fallbackUrl = "https://api.openai.com/v1/chat/completions"
```

#### Fully Offline (Ollama Only)
```toml
# Leave cloud keys empty — Ollama is used automatically
cloudApiKey = ""
fallbackApiKey = ""

# Ollama settings
[ai.connection]
ollamaUrl = "http://localhost:11434/api/chat"
model = "llama3.1"
```

### Tuning Parameters

| Setting | Default | Description |
|---------|---------|-------------|
| `temperature` | `0.7` | Response creativity. `0.0` = robotic/precise, `0.7` = balanced, `1.5` = wild/creative |
| `maxTokens` | `500` | Max length of AI response. Higher = longer replies but more cost. 500 is ideal for tool calling. |
| `maxToolIterations` | `10` | Max tool calls per message. Higher allows longer chains but risks loops. |
| `timeoutMs` | `60000` | HTTP timeout. Increase if using slow local models. |

## Using Claude or GPT via OpenRouter

OpenRouter acts as a universal gateway — it wraps many providers (including OpenAI, Anthropic, Google, Meta) in the standard OpenAI-compatible format. This means you can use Claude, GPT-4o, Gemini, or any other model through a single OpenRouter API key.

Example for Claude 3.5 Sonnet:
```toml
cloudApiKey = "sk-or-v1-your_openrouter_key"
cloudModel = "anthropic/claude-3.5-sonnet"
cloudUrl = "https://openrouter.ai/api/v1/chat/completions"
```

Example for Google Gemini:
```toml
cloudApiKey = "sk-or-v1-your_openrouter_key"
cloudModel = "google/gemini-2.0-flash-001"
cloudUrl = "https://openrouter.ai/api/v1/chat/completions"
```

Browse available models at: https://openrouter.ai/models

## Safety Mechanisms

The AI backend includes several safety features:

- **Deduplication breaker** — If the AI makes 3+ identical tool calls, the loop is forcibly stopped
- **[CANNOT_CRAFT] directive** — Prevents infinite retry loops when materials are unreachable
- **[ASYNC_TASK] marker** — Queued tasks get a single status response; AI doesn't spam tool calls
- **Fallback chain** — Rate limits on one provider seamlessly switch to the next
- **Tool iteration cap** — Hard limit (default 10) prevents runaway agent loops
- **Blocked commands** — Dangerous server commands (op, ban, stop, etc.) are permanently blocked

## Troubleshooting

**"AI not responding"**
- Check that your API key is valid and not expired
- Check `logs/mcai_debug.log` for error details
- Ensure Ollama is installed if using local mode (https://ollama.ai)

**"AI picks the wrong tool"**
- Try being more specific: "strip mine for diamonds" instead of "find diamonds"
- Consider upgrading to a paid model for better tool routing

**"Rate limited / slow responses"**
- Add a fallback provider in `[ai.cloud_fallback]`
- Switch to a paid tier on your provider
- Ollama (local) has no rate limits but requires GPU

**"AI hallucinates / makes up items"**
- Lower temperature to `0.3` for more deterministic responses
- Paid models hallucinate significantly less than free ones
