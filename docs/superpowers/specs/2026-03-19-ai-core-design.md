# AI Core -- Design Spec

## Overview

AI Core is the second sub-project of DollOS, providing the AI intelligence layer that powers the OS's companion experience. It includes an LLM client for multi-model API communication, a conversation engine with persistent memory, a personality system, and a basic agent system for device control.

AI Core runs as an independent system service (`DollOSAIService`) separate from the existing `DollOSService`.

## Architecture

### Service Architecture

```
DollOSAIService (normal app uid)
  - LLM Client
  - Conversation Engine
  - Memory System
  - Personality System
  - Agent System (logic)

DollOSService (system uid)
  - System config (GMS, version)
  - Agent execution (WiFi, Bluetooth, alarms -- on behalf of AIService)
  - AI Task Manager (emergency stop UI)
  - Power button interception
```

DollOSAIService uses normal app uid (NOT sharedUserId=system) to avoid storage issues. System-privileged operations are delegated to DollOSService via Binder IPC.

### Responsibility Split

| Concern | Owner |
|---------|-------|
| API key, personality, memory | DollOSAIService |
| GMS preference, DollOS version | DollOSService |
| LLM API calls | DollOSAIService |
| System operations (WiFi, BT, alarms) | DollOSService (executed on behalf of AIService) |
| AI Task Manager + emergency stop | DollOSService |
| Conversation history, context management | DollOSAIService |

OOBE binds to both services: GMS page writes to DollOSService, API key and personality pages write to DollOSAIService.

## Section 1: LLM Client

### Multi-Model Support

Supports multiple LLM providers through a unified interface. Users configure in Settings:
- **Foreground model** -- used for active conversation (higher quality)
- **Background model** -- used for memory summarization, background tasks (cheaper)

Supported providers (first version): Claude, Grok, OpenAI, custom endpoint.

### Streaming and Response Control

- Streaming responses by default (token-by-token display)
- Non-streaming available as option
- **Emergency stop mechanism:**
  - Double-click power button -> pause ALL AI activity globally
  - Display AI Task Manager modal (system level, managed by DollOSService)
  - Modal shows: task name, description, start time, token usage, estimated cost, conversation context
  - User can cancel individual tasks or resume all
  - Modal has highest priority (same level as power menu), cannot be accessed by AI
  - Requires framework modification (`PhoneWindowManager`) to intercept power button double-click

### Stop Triggers

- Manual: stop button in conversation UI
- Voice: user starts speaking during AI response -> stop current generation
- Hardware: double-click power button -> global emergency stop

### Usage Tracking

Tracks per API call:
- Token count (input + output)
- Estimated cost
- Timestamp
- Classification: foreground/background, model, function (conversation/agent/memory)

Budget controls:
- User sets **warning threshold** -- notification when exceeded
- User sets **hard limit** -- all AI functions stop when exceeded
- Both configurable per day/month in Settings

### Error Handling

API errors (invalid key, rate limit, network failure) are surfaced via Android system notifications, not in the conversation UI. This keeps the conversation clean and the notification system handles retries/alerts.

### Offline Mode

- First version: AI conversation fully stops when offline, local small model fallback for future consideration
- Agent operations that don't need LLM (open app, toggle WiFi/BT) remain functional offline
- Memory search (ObjectBox local) remains functional offline

### No Cache

Caching deferred to future versions.

## Section 2: Conversation Engine

### Conversation Structure

Conversations are automatically segmented by date. Each day is a separate conversation segment (like date dividers in a chat app). Users scroll up to see history. No manual "new conversation" action needed.

### Context Window Management

**Proactive compression:**
1. Monitor context usage continuously
2. At 70-80% capacity, snapshot current conversation for background compression
3. Immediately truncate old messages in foreground, keep recent N messages, continue responding
4. Background compression runs in parallel using foreground model (async API call)
5. When compression completes, merge: summary + new messages accumulated during compression

**Compression produces:**
- A summary of the compressed conversation
- Triggers memory write (important facts extracted before compression)

### Memory Architecture

Inspired by OpenClaw/memsearch. **Markdown files are the single source of truth.** ObjectBox is a pure search index rebuilt from Markdown if corrupted.

**Three-tier structure:**

| Tier | Path | Loading |
|------|------|---------|
| 1 | `MEMORY.md` | Always loaded into context |
| 2 | `memory/YYYY-MM-DD.md` | Today and yesterday auto-loaded |
| 3 | `memory/people/`, `topics/`, `decisions/` | Loaded via search when relevant |

**Search:** Hybrid search using ObjectBox:
- Vector search (semantic similarity)
- BM25 keyword search
- Results merged with configurable weights

**Embedding source:** User-configurable in Settings:
- Cloud API (OpenAI, Voyage, etc.) -- higher quality, requires network
- Local ONNX model (e.g., all-MiniLM-L6-v2, ~23MB) -- offline capable, lower quality

**Storage:** App internal storage (`/data/user/0/org.dollos.ai/files/memory/`), with export/import functionality for user access and backup.

### Memory Write Triggers

Three trigger sources, all feeding into a **single serialized write queue**:

1. **Context threshold** -- when context reaches 70-80%, extract important facts before compression
2. **Idle background** -- when conversation is idle for N minutes, background model reviews recent conversation and extracts memories
3. **Event-based** -- screen lock, app switch, user says "remember this"

**"Remember this" flow:**
1. User says "remember this" (or similar)
2. AI extracts and organizes the information
3. AI presents formatted memory to user: "I'll remember: [content]. Is this correct?"
4. User confirms -> write to memory
5. User corrects -> AI adjusts and re-confirms

**Write failure handling:** Retry 3 times, then persist to temporary storage for retry on next service startup.

### Index Sync

Markdown is source of truth. ObjectBox index rebuilds from Markdown when:
- Files are added/modified/deleted
- Index is corrupted or missing
- Service startup detects inconsistency

## Section 3: Personality System

### Configuration Fields

Inspired by Kindroid. Five user-configurable fields, all injected into the system prompt:

| Field | Limit | Description |
|-------|-------|-------------|
| Backstory | 2500 chars | AI's background, personality, motivations. Written in 3rd person |
| Response Directive | 150 chars | Forced tone and style guide (strongest influence) |
| Dynamism | 0.0 - 1.0 slider | Stability (0) to creativity (1). Maps to LLM temperature and prompt wording |
| Address | 50 chars | How AI addresses the user (name, nickname, honorific) |
| Language Preference | selection | AI response language, whether to mix languages |

No presets. Users write their own personality configuration.

### System Prompt Construction

```
[Response Directive]
[Language Preference]
[Address instruction]
[Backstory]
[Relevant memories from Tier 1 + Tier 2]
[Search results from Tier 3 if applicable]
[Conversation history]
[User message]
```

Dynamism maps to:
- LLM temperature parameter (0.0 -> temp 0.3, 1.0 -> temp 1.2)
- Additional prompt wording adjusting creativity level

## Section 4: Agent System

### Action Interface

Each agent operation implements a unified `Action` interface:

```
Action {
    id: String              // "open_app"
    name: String            // "Open App"
    description: String     // For LLM tool description
    parameters: Schema      // What parameters the action needs
    confirmRequired: Boolean // Default confirmation requirement
    execute(params): Result // Execute the action
}
```

### LLM Integration

Uses LLM tool calling (function calling) to invoke actions. All registered Actions are provided to the LLM as available tools. LLM decides when to call which tool based on conversation context.

For models that don't support tool calling: not supported in v1, may add keyword-based fallback later.

### First Version Actions

| Action | Parameters | Default Confirm | Executor |
|--------|-----------|----------------|----------|
| Open App | package name or app name | No | DollOSAIService (no special permission needed) |
| Set Alarm | time, label | Yes | DollOSService (via Binder) |
| Toggle WiFi | on/off | Yes | DollOSService (via Binder) |
| Toggle Bluetooth | on/off | Yes | DollOSService (via Binder) |

### Confirmation Flow

1. LLM decides to call a tool
2. AIService checks if `confirmRequired` is true for that action
3. If yes: display confirmation to user in conversation ("Should I turn on WiFi?")
4. User confirms -> execute via DollOSService Binder call
5. User denies -> cancel and inform LLM

Users can change confirmation settings per action in Settings.

## Section 5: Emergency Stop (AI Task Manager)

### Trigger

Double-click power button. Requires `PhoneWindowManager` framework modification to intercept.

### Behavior

1. Double-click detected -> DollOSService immediately sends pause signal to DollOSAIService
2. DollOSAIService pauses all:
   - Active streaming responses
   - Background memory processing
   - Agent task execution
   - Pending API calls
3. DollOSService displays Task Manager modal (same z-order as power menu)
4. Modal shows all AI tasks with:
   - Task name and description
   - Start time
   - Token usage and estimated cost
   - Related conversation context
5. User can:
   - Cancel individual tasks
   - Resume all (dismiss modal)
   - Dismiss modal (tapping outside) -> resume all
6. AI cannot access or dismiss this modal

### Implementation

- DollOSService owns the modal UI and power button interception
- DollOSAIService reports task list to DollOSService via Binder
- Pause/resume signals flow from DollOSService to DollOSAIService via Binder

## Section 6: AIDL Interfaces

### IDollOSAIService (new)

```
interface IDollOSAIService {
    // Conversation
    void sendMessage(String message);
    void stopGeneration();
    void pauseAll();
    void resumeAll();

    // Personality
    void setBackstory(String backstory);
    void setResponseDirective(String directive);
    void setDynamism(float value);
    void setAddress(String address);
    void setLanguagePreference(String language);
    String getBackstory();
    String getResponseDirective();
    float getDynamism();
    String getAddress();
    String getLanguagePreference();

    // API Configuration
    void setForegroundModel(String provider, String apiKey, String model);
    void setBackgroundModel(String provider, String apiKey, String model);

    // Memory
    String searchMemory(String query);
    void exportMemory(String destinationPath);
    void importMemory(String sourcePath);

    // Usage
    String getUsageStats();
    void setWarningThreshold(long tokens);
    void setHardLimit(long tokens);

    // Task Manager
    String getActiveTasks(); // JSON list of active tasks
    void cancelTask(String taskId);
}
```

### IDollOSService (updated)

Add to existing interface:

```
// Agent execution (on behalf of AIService)
void executeSystemAction(String actionId, String paramsJson);

// Emergency stop
void showTaskManager();
void hideTaskManager();
```

## Out of Scope

- AI Launcher (separate sub-project -- System UI)
- Avatar system (separate sub-project)
- Voice pipeline / STT / TTS (separate sub-project)
- API key encryption (deferred)
- GMS auto-installation in OOBE
- Cache system
- Advanced agent actions beyond the four listed
