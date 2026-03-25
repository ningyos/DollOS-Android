# DollOS-Server Native Tool Calling Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace RestrictedPython code execution with LLM native tool calling. Remove shell/, guraverse/, LearnerService.

**Architecture:** AgentLoop rewritten to use LLM API tool_use responses. ToolNamespace simplified to generate JSON schemas and dispatch calls. Kmod ToolDefs translate directly. TinyGura and spawn tools removed.

**Tech Stack:** Python, OpenAI-compatible API (tool calling), NATS IPC, asyncio

---

## File Structure

### Existing files modified

```
smolgura/
  gura/
    agent_loop.py          — rewrite: tool_use loop replaces code block detection
    tool_namespace.py       — rewrite: to_tool_schemas() + dispatch(), remove thread proxy
    tools.py                — rewrite: build_tool_schemas() replaces build_tool_env() + get_tool_signatures()
    core.py                 — update: _process() uses new AgentLoop, remove CodeExecutor
    prompts.py              — update: remove code execution instruction renderer
    templates/
      core_identity.j2      — update: replace code instructions with tool calling instructions
  infra/
    openai_api.py           — update: add tools parameter to chat()
  tools/
    __init__.py             — update: remove agent tool imports from load_all_tools()

tests/
  test_gura/
    test_agent_loop.py      — rewrite: test tool_use flow instead of code blocks
    test_tool_namespace.py  — rewrite: test to_tool_schemas() + dispatch()
    test_tools.py           — update: test build_tool_schemas()
```

### Files removed

```
smolgura/
  shell/                   — entire directory (executor.py, guards.py, errors.py, policy.py, transformer.py, __init__.py)
  guraverse/               — entire directory (tinygura.py, registry.py, models.py, __init__.py)
  services/learner.py      — LearnerService
  tools/agents/
    spawn_agent.py
    list_processes.py
    kill_process.py
    create_agent.py
    delete_agent.py
    get_agent_info.py
    list_agents.py
    pc_agent.py
    phone_agent.py

tests/
  test_tools/test_agent_tools.py
  test_tools/test_phone_agent.py
  test_tools/test_pc_agent.py
  test_integration/test_tinygura_tools_e2e.py
```

---

## Task 1: Add `tools` Parameter to LLM Client

**Goal:** Enable `LLMClient.chat()` to forward the `tools` parameter to the OpenAI API, so the LLM can return `tool_calls` in its response.

**Files:**
- Edit: `~/Projects/DollOS-Server/smolgura/infra/openai_api.py`
- Create: `~/Projects/DollOS-Server/tests/test_infra/test_openai_api_tools.py`

- [ ] **Step 1: Write test for tools parameter forwarding**

Create `~/Projects/DollOS-Server/tests/test_infra/test_openai_api_tools.py`:

```python
"""Tests for LLM client tool calling support."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from smolgura.infra.openai_api import LLMClient


@pytest.fixture
def llm_config():
    from smolgura.config import LLMConfig
    return LLMConfig(api_key="test-key", base_url="http://localhost:8000/v1", model="test-model")


@pytest.mark.asyncio
async def test_chat_forwards_tools_param(llm_config):
    """chat() should forward tools= to the OpenAI client."""
    client = LLMClient(llm_config)
    mock_create = AsyncMock(return_value=MagicMock())
    client._client.chat.completions.create = mock_create

    tools = [
        {
            "type": "function",
            "function": {
                "name": "remember",
                "description": "Search memories",
                "parameters": {
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                    "required": ["query"],
                },
            },
        }
    ]
    await client.chat(messages=[{"role": "user", "content": "hi"}], tools=tools)
    call_kwargs = mock_create.call_args
    assert call_kwargs.kwargs.get("tools") == tools


@pytest.mark.asyncio
async def test_chat_without_tools_param(llm_config):
    """chat() without tools= should not send tools to API."""
    client = LLMClient(llm_config)
    mock_create = AsyncMock(return_value=MagicMock())
    client._client.chat.completions.create = mock_create

    await client.chat(messages=[{"role": "user", "content": "hi"}])
    call_kwargs = mock_create.call_args
    assert "tools" not in call_kwargs.kwargs
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_infra/test_openai_api_tools.py -x` — expect fail.

- [ ] **Step 2: Update LLMClient.chat() to accept and forward tools**

Edit `~/Projects/DollOS-Server/smolgura/infra/openai_api.py` — in `chat()` method, the `**kwargs` already forwards extra arguments. Verify by confirming `tools=` is passed through. The current implementation already supports this via `**kwargs`. Run the test — expect pass.

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_infra/test_openai_api_tools.py -x`

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/DollOS-Server
git add tests/test_infra/test_openai_api_tools.py
git commit -m "test: verify LLMClient forwards tools parameter to OpenAI API"
```

---

## Task 2: Rewrite ToolNamespace with `to_tool_schemas()` and `dispatch()`

**Goal:** Replace the attribute-based ToolNamespace (designed for `await ns.remember()` in code execution) with a registry that generates JSON tool schemas and dispatches calls by name.

**Files:**
- Edit: `~/Projects/DollOS-Server/smolgura/gura/tool_namespace.py`
- Edit: `~/Projects/DollOS-Server/tests/test_gura/test_tool_namespace.py`

- [ ] **Step 1: Write tests for new ToolNamespace**

Rewrite `~/Projects/DollOS-Server/tests/test_gura/test_tool_namespace.py`:

```python
"""Tests for ToolNamespace — schema generation and dispatch."""

import pytest

from smolgura.gura.tool_namespace import ToolNamespace


def _make_namespace():
    """Build a ToolNamespace with sample tools."""
    ns = ToolNamespace()

    async def remember(query: str, limit: int = 5) -> dict:
        return {"memories": [f"result for {query}"], "count": limit}

    ns.register(
        name="memory.remember",
        fn=remember,
        description="Search memories by semantic similarity.",
        parameters={
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Search query"},
                "limit": {"type": "integer", "description": "Max results", "default": 5},
            },
            "required": ["query"],
        },
    )

    async def memorize(text: str) -> dict:
        return {"stored": True}

    ns.register(
        name="memory.memorize",
        fn=memorize,
        description="Store a new memory.",
        parameters={
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to store"},
            },
            "required": ["text"],
        },
    )
    return ns


class TestToToolSchemas:
    def test_returns_list_of_openai_tool_dicts(self):
        ns = _make_namespace()
        schemas = ns.to_tool_schemas()
        assert isinstance(schemas, list)
        assert len(schemas) == 2

    def test_schema_format(self):
        ns = _make_namespace()
        schemas = ns.to_tool_schemas()
        schema = schemas[0]
        assert schema["type"] == "function"
        assert "function" in schema
        func = schema["function"]
        assert "name" in func
        assert "description" in func
        assert "parameters" in func

    def test_tool_names_use_dots(self):
        ns = _make_namespace()
        schemas = ns.to_tool_schemas()
        names = {s["function"]["name"] for s in schemas}
        assert "memory.remember" in names
        assert "memory.memorize" in names

    def test_empty_namespace(self):
        ns = ToolNamespace()
        assert ns.to_tool_schemas() == []


@pytest.mark.asyncio
class TestDispatch:
    async def test_dispatch_calls_function(self):
        ns = _make_namespace()
        result = await ns.dispatch("memory.remember", {"query": "weather"})
        assert result == {"memories": ["result for weather"], "count": 5}

    async def test_dispatch_with_all_args(self):
        ns = _make_namespace()
        result = await ns.dispatch("memory.remember", {"query": "test", "limit": 3})
        assert result == {"memories": ["result for test"], "count": 3}

    async def test_dispatch_unknown_tool(self):
        ns = _make_namespace()
        result = await ns.dispatch("nonexistent.tool", {})
        assert "error" in result

    async def test_dispatch_exception_returns_error(self):
        ns = ToolNamespace()

        async def broken(**kwargs):
            raise ValueError("boom")

        ns.register(
            name="broken",
            fn=broken,
            description="A broken tool",
            parameters={"type": "object", "properties": {}},
        )
        result = await ns.dispatch("broken", {})
        assert "error" in result
        assert "boom" in result["error"]


class TestHasTool:
    def test_has_registered_tool(self):
        ns = _make_namespace()
        assert ns.has("memory.remember")
        assert not ns.has("nonexistent")


class TestToolCount:
    def test_len(self):
        ns = _make_namespace()
        assert len(ns) == 2
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_gura/test_tool_namespace.py -x` — expect fail.

- [ ] **Step 2: Rewrite ToolNamespace**

Replace `~/Projects/DollOS-Server/smolgura/gura/tool_namespace.py` with:

```python
"""Tool namespace — schema generation and dispatch for native tool calling.

Replaces the old attribute-based namespace (designed for code execution).
Now serves as a registry that:
1. Stores tool definitions (name, description, parameters, handler fn)
2. Generates JSON tool schemas for the LLM API (to_tool_schemas)
3. Dispatches tool calls by name (dispatch)
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

import structlog

log = structlog.get_logger()


@dataclass
class _ToolEntry:
    """Internal record for a registered tool."""

    name: str
    description: str
    parameters: dict
    fn: Callable


class ToolNamespace:
    """Registry of tools with schema generation and dispatch.

    Tools are registered with a dotted name (e.g. 'memory.remember').
    The namespace generates OpenAI-format tool schemas and routes
    dispatch calls to the correct handler.
    """

    def __init__(self) -> None:
        self._tools: dict[str, _ToolEntry] = {}

    def register(
        self,
        *,
        name: str,
        fn: Callable,
        description: str,
        parameters: dict,
    ) -> None:
        """Register a tool.

        Args:
            name: Dotted tool name (e.g. 'memory.remember').
            fn: Async handler function. Called with **kwargs from LLM arguments.
            description: Human-readable description for LLM.
            parameters: JSON Schema for parameters.
        """
        self._tools[name] = _ToolEntry(
            name=name,
            description=description,
            parameters=parameters,
            fn=fn,
        )

    def has(self, name: str) -> bool:
        """Check if a tool is registered."""
        return name in self._tools

    def to_tool_schemas(self) -> list[dict[str, Any]]:
        """Generate OpenAI-format tool schemas for all registered tools.

        Returns:
            List of dicts like:
            [{"type": "function", "function": {"name": ..., "description": ..., "parameters": ...}}]
        """
        schemas: list[dict[str, Any]] = []
        for entry in self._tools.values():
            schemas.append(
                {
                    "type": "function",
                    "function": {
                        "name": entry.name,
                        "description": entry.description,
                        "parameters": entry.parameters,
                    },
                }
            )
        return schemas

    async def dispatch(self, tool_name: str, arguments: dict[str, Any]) -> Any:
        """Dispatch a tool call by name.

        Args:
            tool_name: The tool name (must match a registered tool).
            arguments: Keyword arguments from the LLM.

        Returns:
            Tool result (usually a dict). On error, returns {"error": "..."}.
        """
        entry = self._tools.get(tool_name)
        if entry is None:
            log.warning("dispatch: unknown tool", tool=tool_name)
            return {"error": f"unknown tool: {tool_name}"}
        try:
            return await entry.fn(**arguments)
        except Exception as e:
            log.warning("dispatch: tool error", tool=tool_name, error=str(e))
            return {"error": f"{type(e).__name__}: {e}"}

    def __len__(self) -> int:
        return len(self._tools)

    def __repr__(self) -> str:
        names = sorted(self._tools.keys())
        return f"<ToolNamespace: {', '.join(names)}>"
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_gura/test_tool_namespace.py -x` — expect pass.

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/DollOS-Server
git add smolgura/gura/tool_namespace.py tests/test_gura/test_tool_namespace.py
git commit -m "refactor: rewrite ToolNamespace for native tool calling (schema + dispatch)"
```

---

## Task 3: Rewrite AgentLoop for Tool Calling

**Goal:** Replace code block detection (`<code>...</code>` regex) with LLM native tool calling. The loop now: call LLM with tools -> parse tool_calls -> dispatch -> add tool results -> repeat until LLM returns plain text.

**Files:**
- Edit: `~/Projects/DollOS-Server/smolgura/gura/agent_loop.py`
- Edit: `~/Projects/DollOS-Server/tests/test_gura/test_agent_loop.py`

- [ ] **Step 1: Write tests for new AgentLoop**

Rewrite `~/Projects/DollOS-Server/tests/test_gura/test_agent_loop.py`:

```python
"""Tests for AgentLoop — native tool calling."""

from unittest.mock import AsyncMock, MagicMock

import pytest

from smolgura.gura.agent_loop import AgentContext, AgentLoop, StepResult
from smolgura.gura.tool_namespace import ToolNamespace


# -- Helpers --

def _make_text_response(content: str):
    """Build a fake LLM response with plain text (no tool calls)."""
    msg = MagicMock()
    msg.content = content
    msg.tool_calls = None
    msg.reasoning_content = None
    choice = MagicMock()
    choice.message = msg
    choice.finish_reason = "stop"
    resp = MagicMock()
    resp.choices = [choice]
    return resp


def _make_tool_call_response(tool_calls: list[dict]):
    """Build a fake LLM response with tool_calls.

    Each dict: {"id": str, "name": str, "arguments": str (JSON)}.
    """
    msg = MagicMock()
    msg.content = None
    msg.reasoning_content = None
    tc_objects = []
    for tc in tool_calls:
        tc_obj = MagicMock()
        tc_obj.id = tc["id"]
        tc_obj.type = "function"
        tc_obj.function = MagicMock()
        tc_obj.function.name = tc["name"]
        tc_obj.function.arguments = tc["arguments"]
        tc_objects.append(tc_obj)
    msg.tool_calls = tc_objects
    choice = MagicMock()
    choice.message = msg
    choice.finish_reason = "tool_calls"
    resp = MagicMock()
    resp.choices = [choice]
    return resp


def _make_namespace():
    ns = ToolNamespace()

    async def remember(query: str, limit: int = 5):
        return {"memories": [f"found: {query}"], "count": limit}

    ns.register(
        name="memory.remember",
        fn=remember,
        description="Search memories.",
        parameters={
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "limit": {"type": "integer", "default": 5},
            },
            "required": ["query"],
        },
    )
    return ns


def _make_context(**overrides):
    defaults = dict(agent_id="test", system_prompt="You are a test agent.", capabilities=[])
    defaults.update(overrides)
    return AgentContext(**defaults)


# -- Tests --

@pytest.mark.asyncio
class TestAgentLoopTextResponse:
    async def test_plain_text_returns_response(self):
        """LLM returns plain text -> StepResult.response is set."""
        llm = AsyncMock()
        llm.chat = AsyncMock(return_value=_make_text_response("The answer is 42."))
        ctx = _make_context()
        loop = AgentLoop(llm=llm, context=ctx, tools=_make_namespace())
        loop.add_event("user", "What is the meaning of life?")

        result = await loop.step()
        assert result.response == "The answer is 42."
        assert result.tool_calls == []
        assert result.done is True

    async def test_empty_response(self):
        llm = AsyncMock()
        llm.chat = AsyncMock(return_value=_make_text_response(""))
        ctx = _make_context()
        loop = AgentLoop(llm=llm, context=ctx, tools=_make_namespace())
        loop.add_event("user", "hi")

        result = await loop.step()
        assert result.response is None
        assert result.done is True


@pytest.mark.asyncio
class TestAgentLoopToolCalls:
    async def test_tool_call_dispatched(self):
        """LLM returns tool_calls -> dispatched and results added to messages."""
        llm = AsyncMock()
        llm.chat = AsyncMock(
            return_value=_make_tool_call_response(
                [{"id": "call_1", "name": "memory.remember", "arguments": '{"query": "weather"}'}]
            )
        )
        ns = _make_namespace()
        ctx = _make_context()
        loop = AgentLoop(llm=llm, context=ctx, tools=ns)
        loop.add_event("user", "What is the weather?")

        result = await loop.step()
        assert result.done is False
        assert len(result.tool_calls) == 1
        assert result.tool_calls[0]["name"] == "memory.remember"
        assert result.tool_calls[0]["result"] == {"memories": ["found: weather"], "count": 5}

        # Tool result should be in messages
        tool_msgs = [m for m in loop.messages if m["role"] == "tool"]
        assert len(tool_msgs) == 1
        assert tool_msgs[0]["tool_call_id"] == "call_1"

    async def test_multiple_parallel_tool_calls(self):
        """LLM returns multiple tool_calls in one response -> all dispatched."""
        llm = AsyncMock()
        llm.chat = AsyncMock(
            return_value=_make_tool_call_response(
                [
                    {"id": "call_1", "name": "memory.remember", "arguments": '{"query": "a"}'},
                    {"id": "call_2", "name": "memory.remember", "arguments": '{"query": "b"}'},
                ]
            )
        )
        ns = _make_namespace()
        ctx = _make_context()
        loop = AgentLoop(llm=llm, context=ctx, tools=ns)
        loop.add_event("user", "search both")

        result = await loop.step()
        assert len(result.tool_calls) == 2
        tool_msgs = [m for m in loop.messages if m["role"] == "tool"]
        assert len(tool_msgs) == 2

    async def test_unknown_tool_returns_error(self):
        """Unknown tool name -> error result added to messages."""
        llm = AsyncMock()
        llm.chat = AsyncMock(
            return_value=_make_tool_call_response(
                [{"id": "call_1", "name": "nonexistent.tool", "arguments": "{}"}]
            )
        )
        ns = _make_namespace()
        ctx = _make_context()
        loop = AgentLoop(llm=llm, context=ctx, tools=ns)
        loop.add_event("user", "do something")

        result = await loop.step()
        assert "error" in result.tool_calls[0]["result"]


@pytest.mark.asyncio
class TestAgentLoopToolsPassedToLLM:
    async def test_tools_schemas_sent_to_llm(self):
        """LLM.chat() should receive tools= with schemas from ToolNamespace."""
        llm = AsyncMock()
        llm.chat = AsyncMock(return_value=_make_text_response("ok"))
        ns = _make_namespace()
        ctx = _make_context()
        loop = AgentLoop(llm=llm, context=ctx, tools=ns)
        loop.add_event("user", "hi")

        await loop.step()
        call_kwargs = llm.chat.call_args
        assert "tools" in call_kwargs.kwargs
        tools = call_kwargs.kwargs["tools"]
        assert len(tools) == 1  # one tool registered
        assert tools[0]["function"]["name"] == "memory.remember"

    async def test_no_tools_when_namespace_empty(self):
        """Empty ToolNamespace -> tools= not sent to LLM."""
        llm = AsyncMock()
        llm.chat = AsyncMock(return_value=_make_text_response("ok"))
        ns = ToolNamespace()
        ctx = _make_context()
        loop = AgentLoop(llm=llm, context=ctx, tools=ns)
        loop.add_event("user", "hi")

        await loop.step()
        call_kwargs = llm.chat.call_args
        assert "tools" not in call_kwargs.kwargs or call_kwargs.kwargs.get("tools") is None


@pytest.mark.asyncio
class TestAgentLoopThinking:
    async def test_thinking_content_stripped(self):
        """Qwen3-VL thinking tags stripped from response."""
        llm = AsyncMock()
        llm.chat = AsyncMock(
            return_value=_make_text_response("<think>Let me think...</think>\nThe answer is 42.")
        )
        ctx = _make_context()
        loop = AgentLoop(llm=llm, context=ctx, tools=_make_namespace())
        loop.add_event("user", "question")

        result = await loop.step()
        assert result.response == "The answer is 42."


@pytest.mark.asyncio
class TestAgentLoopHistory:
    async def test_trim_history(self):
        """History should be trimmed to max_history."""
        llm = AsyncMock()
        llm.chat = AsyncMock(return_value=_make_text_response("ok"))
        ctx = _make_context(max_history=3)
        loop = AgentLoop(llm=llm, context=ctx, tools=_make_namespace())

        for i in range(10):
            loop.add_event("user", f"msg {i}")
            await loop.step()

        assert len(loop.messages) <= 1 + 3 * 2

    async def test_reset(self):
        llm = AsyncMock()
        llm.chat = AsyncMock(return_value=_make_text_response("ok"))
        ctx = _make_context()
        loop = AgentLoop(llm=llm, context=ctx, tools=_make_namespace())
        loop.add_event("user", "hi")
        await loop.step()
        loop.reset()
        assert len(loop.messages) == 1  # only system


@pytest.mark.asyncio
class TestAgentLoopModel:
    async def test_model_kwarg_passed(self):
        llm = AsyncMock()
        llm.chat = AsyncMock(return_value=_make_text_response("ok"))
        ctx = _make_context()
        loop = AgentLoop(llm=llm, context=ctx, tools=_make_namespace(), model="my-model")
        loop.add_event("user", "hi")
        await loop.step()

        assert llm.chat.call_args.kwargs.get("model") == "my-model"

    async def test_no_model_kwarg_when_none(self):
        llm = AsyncMock()
        llm.chat = AsyncMock(return_value=_make_text_response("ok"))
        ctx = _make_context()
        loop = AgentLoop(llm=llm, context=ctx, tools=_make_namespace())
        loop.add_event("user", "hi")
        await loop.step()

        assert "model" not in llm.chat.call_args.kwargs
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_gura/test_agent_loop.py -x` — expect fail.

- [ ] **Step 2: Rewrite AgentLoop**

Replace `~/Projects/DollOS-Server/smolgura/gura/agent_loop.py` with:

```python
"""Agent loop — native tool calling.

Replaces the old code-block-detection loop. Now:
1. Call LLM with tools schemas
2. Parse tool_calls from response
3. Dispatch each tool call via ToolNamespace
4. Add tool results to messages
5. Repeat until LLM returns plain text (no tool_calls) = final response
"""

from __future__ import annotations

import json
import time
from typing import Any

import structlog
from pydantic import BaseModel, Field

log = structlog.get_logger()


class StepResult(BaseModel):
    """Result of one agent loop step."""

    response: str | None = None
    tool_calls: list[dict[str, Any]] = Field(default_factory=list)
    done: bool = False


class AgentContext(BaseModel):
    """Context for an agent loop instance."""

    agent_id: str
    system_prompt: str
    goal: str = ""
    capabilities: list[str]
    max_history: int = 20


class AgentLoop:
    """Think-act-observe loop using LLM native tool calling.

    Works with any LLMClient-compatible object. ToolNamespace provides
    schema generation and dispatch.
    """

    def __init__(
        self,
        llm: Any,
        context: AgentContext,
        tools: Any = None,
        model: str | None = None,
        usage: Any = None,
    ) -> None:
        self._llm = llm
        self._context = context
        self._tools = tools  # ToolNamespace
        self._model = model
        self._usage = usage
        self._messages: list[dict[str, Any]] = [
            {"role": "system", "content": self._context.system_prompt}
        ]

    @property
    def messages(self) -> list[dict[str, Any]]:
        return list(self._messages)

    def set_system_message(self, content: str) -> None:
        """Replace the system message (first message in history)."""
        self._messages[0] = {"role": "system", "content": content}

    def restore_messages(self, messages: list[dict[str, Any]]) -> None:
        """Replace entire message history (e.g. resume from paused item)."""
        self._messages = list(messages)

    def reset(self) -> None:
        """Reset to only the system message."""
        if len(self._messages) > 1:
            log.debug("reset history", agent_id=self._context.agent_id, discarded=len(self._messages) - 1)
        self._messages = [self._messages[0]]

    def add_event(self, role: str, content: str | list) -> None:
        """Add an event (user message, etc.) to context."""
        self._messages.append({"role": role, "content": content})

    def set_tools(self, tools: Any) -> None:
        """Replace the ToolNamespace (for rebuilding tools per-item)."""
        self._tools = tools

    async def step(self) -> StepResult:
        """Execute one think-act iteration.

        1. Call LLM with tool schemas
        2. If response has tool_calls: dispatch all, add results, return done=False
        3. If response has text only: return done=True
        """
        agent_id = self._context.agent_id
        self._trim_history()

        # Build chat kwargs
        chat_kwargs: dict[str, Any] = {
            "messages": self._messages,
            "temperature": 0.7,
        }
        if self._model is not None:
            chat_kwargs["model"] = self._model

        # Add tool schemas if tools are available
        if self._tools is not None and len(self._tools) > 0:
            chat_kwargs["tools"] = self._tools.to_tool_schemas()

        # Call LLM
        t0 = time.perf_counter()
        response = await self._llm.chat(**chat_kwargs)
        latency_ms = int((time.perf_counter() - t0) * 1000)
        if self._usage is not None:
            await self._usage.record(
                response,
                purpose="chat",
                agent_id=agent_id,
                model=self._model or "unknown",
                latency_ms=latency_ms,
            )

        choice = response.choices[0]
        message = choice.message

        # Log reasoning if present (Qwen3)
        reasoning = getattr(message, "reasoning_content", None)
        if reasoning:
            log.info("reasoning", agent_id=agent_id, thinking=reasoning)

        # Strip inline thinking tags
        if message.content and "</think>" in message.content:
            parts = message.content.split("</think>", 1)
            thinking_text = parts[0].strip()
            message.content = parts[1].strip()
            if not reasoning and thinking_text:
                log.info("reasoning", agent_id=agent_id, thinking=thinking_text)

        result = StepResult()

        # Check for tool calls
        tool_calls = getattr(message, "tool_calls", None)
        if tool_calls:
            # Add assistant message with tool_calls to history
            # Build the message dict matching OpenAI format
            assistant_msg: dict[str, Any] = {
                "role": "assistant",
                "content": message.content,
                "tool_calls": [
                    {
                        "id": tc.id,
                        "type": "function",
                        "function": {
                            "name": tc.function.name,
                            "arguments": tc.function.arguments,
                        },
                    }
                    for tc in tool_calls
                ],
            }
            self._messages.append(assistant_msg)

            # Dispatch each tool call
            for tc in tool_calls:
                tool_name = tc.function.name
                try:
                    arguments = json.loads(tc.function.arguments)
                except (json.JSONDecodeError, TypeError):
                    arguments = {}

                log.info("tool call", agent_id=agent_id, tool=tool_name, args=arguments)

                if self._tools is not None:
                    tool_result = await self._tools.dispatch(tool_name, arguments)
                else:
                    tool_result = {"error": "no tools available"}

                result.tool_calls.append({
                    "id": tc.id,
                    "name": tool_name,
                    "arguments": arguments,
                    "result": tool_result,
                })

                # Add tool result to messages
                self._messages.append({
                    "role": "tool",
                    "tool_call_id": tc.id,
                    "content": json.dumps(tool_result, ensure_ascii=False, default=str),
                })

            result.done = False
        else:
            # Plain text response
            content = message.content or ""
            if content.strip():
                result.response = content.strip()
                self._messages.append({"role": "assistant", "content": content})
                log.info("text response", agent_id=agent_id, response=result.response[:200])
            else:
                log.warning("empty response", agent_id=agent_id)
            result.done = True

        self._trim_history()
        return result

    def _trim_history(self) -> None:
        """Keep only system prompt + last N message pairs."""
        max_msgs = 1 + self._context.max_history * 2
        if len(self._messages) > max_msgs:
            dropped = len(self._messages) - max_msgs
            log.warning(
                "trim history",
                agent_id=self._context.agent_id,
                before=len(self._messages),
                after=max_msgs,
                dropped=dropped,
            )
            system = self._messages[0]
            self._messages = [system] + self._messages[-(max_msgs - 1):]
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_gura/test_agent_loop.py -x` — expect pass.

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/DollOS-Server
git add smolgura/gura/agent_loop.py tests/test_gura/test_agent_loop.py
git commit -m "refactor: rewrite AgentLoop for native tool calling"
```

---

## Task 4: Rewrite GuraTools — `build_tool_namespace()` Replaces `build_tool_env()` + `get_tool_signatures()`

**Goal:** Replace the two separate methods (`build_tool_env()` for code execution, `get_tool_signatures()` for Python signature strings) with a single `build_tool_namespace()` that returns a `ToolNamespace` ready for both schema generation and dispatch.

**Files:**
- Edit: `~/Projects/DollOS-Server/smolgura/gura/tools.py`
- Edit: `~/Projects/DollOS-Server/tests/test_gura/test_tools.py`

- [ ] **Step 1: Write tests for `build_tool_namespace()`**

Add to `~/Projects/DollOS-Server/tests/test_gura/test_tools.py`:

```python
"""Tests for GuraTools.build_tool_namespace()."""

from unittest.mock import AsyncMock, MagicMock

import pytest

from smolgura.gura.tools import GuraTools


@pytest.fixture
def gura_tools():
    memory = MagicMock()
    memory.search = AsyncMock(return_value={"entries": []})
    memory.store = AsyncMock(return_value={"stored": True})
    embedding = MagicMock()
    embedding.embed = AsyncMock(return_value=[0.0] * 384)
    return GuraTools(memory=memory, embedding=embedding, agent_id="test")


class TestBuildToolNamespace:
    def test_returns_tool_namespace(self, gura_tools):
        ns = gura_tools.build_tool_namespace()
        assert hasattr(ns, "to_tool_schemas")
        assert hasattr(ns, "dispatch")

    def test_core_tools_registered(self, gura_tools):
        ns = gura_tools.build_tool_namespace()
        assert ns.has("memory.remember")
        assert ns.has("memory.memorize")
        assert ns.has("memory.learn_skill")
        assert ns.has("memory.recall_skills")

    def test_schemas_are_valid_openai_format(self, gura_tools):
        ns = gura_tools.build_tool_namespace()
        schemas = ns.to_tool_schemas()
        assert len(schemas) > 0
        for s in schemas:
            assert s["type"] == "function"
            assert "name" in s["function"]
            assert "description" in s["function"]
            assert "parameters" in s["function"]

    @pytest.mark.asyncio
    async def test_dispatch_remember(self, gura_tools):
        ns = gura_tools.build_tool_namespace()
        result = await ns.dispatch("memory.remember", {"query": "test"})
        assert isinstance(result, dict)
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_gura/test_tools.py::TestBuildToolNamespace -x` — expect fail.

- [ ] **Step 2: Add `build_tool_namespace()` to GuraTools**

Edit `~/Projects/DollOS-Server/smolgura/gura/tools.py` — add a new method `build_tool_namespace()` that:

1. Creates a `ToolNamespace()`
2. Registers core tools (`memory.remember`, `memory.memorize`, `memory.learn_skill`, `memory.recall_skills`) with their JSON schemas and handler functions
3. Registers task tools (`task.add`, `task.remove`, `task.update`) if stack is provided
4. Registers file-based tools (calendar, schedule, logging, web) with schemas from their `ToolDef.to_openai()` and wrapped handlers
5. Registers kmod tools with schemas from `collect_tools()` and wrapped handlers
6. Registers remote platform tools with schemas and wrapped handlers
7. Registers vision/viewpoint/image_memory tools if available
8. Returns the `ToolNamespace`

Each core tool registration uses the existing JSON schema (matching the current signature constants) and wraps the existing handler methods (`_remember`, `_memorize`, etc.).

The key schemas for core tools:

```python
REMEMBER_SCHEMA = {
    "type": "object",
    "properties": {
        "query": {"type": "string", "description": "Search query"},
        "limit": {"type": "integer", "description": "Max results", "default": 5},
    },
    "required": ["query"],
}

MEMORIZE_SCHEMA = {
    "type": "object",
    "properties": {
        "text": {"type": "string", "description": "Text to store"},
        "metadata": {"type": "object", "description": "Optional metadata", "default": {}},
    },
    "required": ["text"],
}

LEARN_SKILL_SCHEMA = {
    "type": "object",
    "properties": {
        "content": {"type": "string", "description": "Skill content"},
        "skill_type": {"type": "string", "description": "Skill type", "default": "procedural"},
    },
    "required": ["content"],
}

RECALL_SKILLS_SCHEMA = {
    "type": "object",
    "properties": {
        "query": {"type": "string", "description": "Search query"},
        "limit": {"type": "integer", "description": "Max results", "default": 3},
    },
    "required": ["query"],
}

ADD_TASK_SCHEMA = {
    "type": "object",
    "properties": {
        "content": {"type": "string", "description": "Task content. Prefix with 'goal: ' for sub-goals."},
    },
    "required": ["content"],
}

REMOVE_TASK_SCHEMA = {
    "type": "object",
    "properties": {
        "task_id": {"type": "string", "description": "ID of the task to remove"},
    },
    "required": ["task_id"],
}

UPDATE_TASK_SCHEMA = {
    "type": "object",
    "properties": {
        "task_id": {"type": "string", "description": "ID of the task to update"},
        "content": {"type": "string", "description": "New content"},
    },
    "required": ["task_id", "content"],
}
```

For file-based tools: use `td.to_openai()["function"]["parameters"]` directly.
For kmod tools: use `tool_def["function"]["parameters"]` and `tool_def["function"]["description"]` directly.
For remote/platform tools: use `tool_info.parameters` for schema and wrap via `_tool_client.call_tool()`.

Keep `build_tool_env()` and `get_tool_signatures()` temporarily for backward compat — mark deprecated.

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_gura/test_tools.py::TestBuildToolNamespace -x` — expect pass.

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/DollOS-Server
git add smolgura/gura/tools.py tests/test_gura/test_tools.py
git commit -m "feat: add build_tool_namespace() to GuraTools for native tool calling"
```

---

## Task 5: Update `GuraCore._process()` to Use New AgentLoop

**Goal:** Rewrite `_process()` to use the new AgentLoop (tool calling) instead of CodeExecutor. Remove repeat detection (LLM handles this natively). Remove `_execute_code`. Remove `CodeExecutor` usage.

**Files:**
- Edit: `~/Projects/DollOS-Server/smolgura/gura/core.py`

- [ ] **Step 1: Write test for new _process loop**

Add to `~/Projects/DollOS-Server/tests/test_gura/test_core_tool_calling.py`:

```python
"""Tests for GuraCore._process() with native tool calling."""

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from smolgura.gura.agent_loop import AgentContext, AgentLoop, StepResult
from smolgura.gura.core import GuraCore
from smolgura.kernel.capability import CapabilityManager
from smolgura.kernel.ipc import FakeIPCBus
from smolgura.kernel.process_manager import ProcessManager


def _make_text_step(text: str) -> StepResult:
    return StepResult(response=text, done=True)


def _make_tool_step(tool_calls: list[dict]) -> StepResult:
    return StepResult(tool_calls=tool_calls, done=False)


@pytest.fixture
def core():
    ipc = FakeIPCBus()
    pm = ProcessManager(ipc)
    cap = CapabilityManager()

    memory = MagicMock()
    memory.search = AsyncMock(return_value={"entries": []})
    memory.store = AsyncMock(return_value={"stored": True})
    embedding = MagicMock()
    embedding.embed = AsyncMock(return_value=[0.0] * 384)

    from smolgura.gura.tools import GuraTools
    tools = GuraTools(memory=memory, embedding=embedding, agent_id="gura")

    llm = AsyncMock()
    c = GuraCore(
        ipc=ipc,
        process_manager=pm,
        cap_manager=cap,
        system_prompt="test",
        tools=tools,
        llm=llm,
    )
    return c


@pytest.mark.asyncio
async def test_process_uses_tool_namespace(core):
    """_process should build ToolNamespace and pass to AgentLoop."""
    # Verify GuraCore no longer has _code_executor attribute
    assert not hasattr(core, "_code_executor"), \
        "GuraCore should no longer have _code_executor after migration"
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_gura/test_core_tool_calling.py -x` — expect fail (core still has _code_executor).

- [ ] **Step 2: Update GuraCore.__init__ — remove CodeExecutor, ShellPolicy imports**

Edit `~/Projects/DollOS-Server/smolgura/gura/core.py`:

1. Remove imports: `CodeExecutor`, `ShellPolicy`, `ActionType` (from agent_loop), `SystemFault` (from shell.errors)
2. Remove `__init__` parameters: `policy`
3. Remove `self._code_executor = CodeExecutor(policy)`
4. Update `AgentLoop` construction: pass `tools=None` (tools are set per-item in `_process`)

- [ ] **Step 3: Rewrite `_process()` method**

Replace the `_process()` method in `~/Projects/DollOS-Server/smolgura/gura/core.py`. The new loop:

```python
async def _process(self, item: StackItem) -> ChatResult:
    """Process a stack item using native tool calling.

    1. Build ToolNamespace for this item
    2. Set up AgentLoop with tools
    3. Loop: step() -> if tool_calls, continue; if text response, done
    """
    self._ensure_llm()

    # Reset reply tracker
    reply_queue = getattr(self._tools, "_terminal_reply_queue", None)
    if reply_queue:
        reply_queue.reset()

    # Auto-recall relevant memories
    await self._auto_recall(item.content)

    # Build sibling context
    siblings = None
    if item.parent_id:
        all_siblings = await self._stack.get_children(item.parent_id)
        siblings = [s for s in all_siblings if s.id != item.id
                     and s.status in (ItemStatus.DONE, ItemStatus.FAILED)]

    # Load images from metadata
    images: list[bytes] = []
    rustfs_keys = item.metadata.get("rustfs_keys", [])
    if rustfs_keys and self._rustfs:
        for key in rustfs_keys:
            try:
                img_bytes = await self._rustfs.get_object(key, bucket=self._rustfs_bucket)
                images.append(img_bytes)
            except Exception:
                log.warning("failed to load image from rustfs", key=key)

    if images:
        from smolgura.vision.viewpoint import ImageSource
        try:
            self._viewpoint.load_image(images[0], ImageSource.SCREEN, "event")
        except Exception:
            log.warning("failed to load image into viewpoint")

    # Restore or start fresh
    artifacts: list[dict[str, Any]] = []
    if item.saved_messages:
        self._loop.restore_messages(item.saved_messages)
        artifacts = list(item.saved_artifacts or [])
    else:
        self._loop.reset()
        content = build_content_with_images(item.content, images) if images else item.content
        self._set_system_prompt(item)
        self._inject_context(item, siblings=siblings)
        self._loop.add_event("user", content)

    # Build tool namespace for this item
    vision_ops = self._get_vision_ops()
    platform = item.source or "terminal"
    if self._tools:
        tool_ns = self._tools.build_tool_namespace(
            stack=self._stack,
            current_item=item,
            viewpoint=self._viewpoint,
            vision_ops=vision_ops,
            image_memory=self._image_memory,
            platform=platform,
        )
    else:
        from smolgura.gura.tool_namespace import ToolNamespace
        tool_ns = ToolNamespace()

    self._loop.set_tools(tool_ns)

    # Main loop: step until LLM produces text (no more tool calls)
    for step_num in range(self._max_steps):
        result = await self._loop.step()

        if result.tool_calls:
            # Log tool calls
            for tc in result.tool_calls:
                artifacts.append({
                    "type": "tool_call",
                    "name": tc["name"],
                    "arguments": tc["arguments"],
                    "result": str(tc["result"]),
                })

                # Publish tool events for monitor
                try:
                    await self._ipc.publish("gura", "event.tool.start", {
                        "item_id": str(item.id),
                        "step": step_num,
                        "tool": tc["name"],
                        "arguments": tc["arguments"],
                        **self._monitor_extras(item, "exec"),
                    })
                    await self._ipc.publish("gura", "event.tool.result", {
                        "item_id": str(item.id),
                        "step": step_num,
                        "tool": tc["name"],
                        "result": str(tc["result"]),
                        **self._monitor_extras(item, "exec"),
                    })
                except Exception:
                    pass

            # Check if children were created (task decomposition)
            children = await self._stack.get_children(item.id)
            if children:
                log.info("item decomposed", agent_id=self._agent_id, item_id=item.id, children=len(children))
                return ChatResult(artifacts=artifacts, result_text="decomposed into sub-tasks")

            continue  # Next step

        # Text response — done
        if result.response:
            return ChatResult(
                artifacts=artifacts,
                replied=True,
                result_text=result.response,
            )
        else:
            return ChatResult(artifacts=artifacts, result_text="(no response)")

    log.warning("max steps reached", agent_id=self._agent_id, item_id=item.id)
    return ChatResult(artifacts=artifacts, result_text="max steps reached")
```

- [ ] **Step 4: Remove `_execute_code` method and `_inject_pending_images` method**

These are no longer needed. Remove them from `core.py`.

- [ ] **Step 5: Run tests**

```bash
cd ~/Projects/DollOS-Server && python -m pytest tests/test_gura/test_core_tool_calling.py -x
```

- [ ] **Step 6: Commit**

```bash
cd ~/Projects/DollOS-Server
git add smolgura/gura/core.py tests/test_gura/test_core_tool_calling.py
git commit -m "refactor: rewrite GuraCore._process() for native tool calling"
```

---

## Task 6: Update Prompt Templates — Remove Code Execution Instructions

**Goal:** Replace `<code>...</code>` instructions with tool calling instructions in the system prompt template.

**Files:**
- Edit: `~/Projects/DollOS-Server/smolgura/gura/templates/core_identity.j2`

- [ ] **Step 1: Update core_identity.j2**

Edit `~/Projects/DollOS-Server/smolgura/gura/templates/core_identity.j2` — replace the `[How Gura works]` section:

Replace:
```
[How Gura works]
- Plain text for conversation.
- <code>...</code> for anything needing data, computation, memory, or actions. Never answer from imagination when code can verify.
- Use tools directly. No narration, no announcements.
- memory.learn_skill() when solving something reusable.
- Think through problems before acting. If it needs multiple steps, use task.add().
- After code executes, you see the result. Decide: continue, reply, memorize, or stop.
- Reply when you want to respond. Don't reply if there's nothing to say.
- memory.memorize() when you learn something worth keeping.

Simple things: just do it.
Complex things: task.add() to break down, each task runs independently.
```

With:
```
[How Gura works]
- Plain text for conversation.
- Use tools for anything needing data, memory, or actions. Never answer from imagination when a tool can verify.
- Call tools directly. No narration, no announcements.
- memory.learn_skill when solving something reusable.
- Think through problems before acting. If it needs multiple steps, use task.add.
- After tool results come back, decide: continue, reply, memorize, or stop.
- Reply when you want to respond. Don't reply if there's nothing to say.
- memory.memorize when you learn something worth keeping.

Simple things: just do it.
Complex things: task.add to break down, each task runs independently.
```

- [ ] **Step 2: Commit**

```bash
cd ~/Projects/DollOS-Server
git add smolgura/gura/templates/core_identity.j2
git commit -m "docs: update system prompt for tool calling (remove code block instructions)"
```

---

## Task 7: Remove `shell/` Directory

**Goal:** Delete the entire `shell/` directory (RestrictedPython executor, guards, policy, transformer, errors).

**Files:**
- Delete: `~/Projects/DollOS-Server/smolgura/shell/` (entire directory)
- Edit: any remaining imports of `shell.*` (should already be removed from core.py in Task 5)

- [ ] **Step 1: Verify no remaining imports**

```bash
cd ~/Projects/DollOS-Server
grep -r "from smolgura.shell" smolgura/ --include="*.py" | grep -v "shell/"
grep -r "import smolgura.shell" smolgura/ --include="*.py" | grep -v "shell/"
```

Fix any remaining imports found. Key files to check:
- `smolgura/gura/core.py` — should already be cleaned in Task 5
- `smolgura/guraverse/tinygura.py` — will be removed in Task 8

- [ ] **Step 2: Delete shell directory**

```bash
cd ~/Projects/DollOS-Server
rm -rf smolgura/shell/
```

- [ ] **Step 3: Remove shell-related test files**

```bash
cd ~/Projects/DollOS-Server
# No dedicated shell test directory, but clean up references in existing tests
grep -r "from smolgura.shell" tests/ --include="*.py" -l
# Fix or remove affected test files
```

- [ ] **Step 4: Verify nothing is broken**

```bash
cd ~/Projects/DollOS-Server
python -m pytest tests/test_gura/ -x --ignore=tests/test_gura/test_vision_tools.py
```

- [ ] **Step 5: Commit**

```bash
cd ~/Projects/DollOS-Server
git add -A
git commit -m "refactor: remove shell/ directory (RestrictedPython executor)"
```

---

## Task 8: Remove `guraverse/` Directory

**Goal:** Delete TinyGura runtime, AgentRegistry, and related models.

**Files:**
- Delete: `~/Projects/DollOS-Server/smolgura/guraverse/` (entire directory)
- Delete: `~/Projects/DollOS-Server/tests/test_integration/test_tinygura_tools_e2e.py`

- [ ] **Step 1: Check for imports of guraverse**

```bash
cd ~/Projects/DollOS-Server
grep -r "from smolgura.guraverse" smolgura/ --include="*.py"
grep -r "import smolgura.guraverse" smolgura/ --include="*.py"
grep -r "guraverse" smolgura/ --include="*.py"
```

Remove or update any remaining references (e.g., in `core.py` if `_spawn_tinygura` exists, in service registrations).

- [ ] **Step 2: Delete guraverse directory**

```bash
cd ~/Projects/DollOS-Server
rm -rf smolgura/guraverse/
rm -f tests/test_integration/test_tinygura_tools_e2e.py
```

- [ ] **Step 3: Verify**

```bash
cd ~/Projects/DollOS-Server
python -m pytest tests/test_gura/ -x
```

- [ ] **Step 4: Commit**

```bash
cd ~/Projects/DollOS-Server
git add -A
git commit -m "refactor: remove guraverse/ directory (TinyGura runtime)"
```

---

## Task 9: Remove LearnerService

**Goal:** Delete the experience submission/review service that depended on TinyGura.

**Files:**
- Delete: `~/Projects/DollOS-Server/smolgura/services/learner.py`

- [ ] **Step 1: Check for LearnerService usage**

```bash
cd ~/Projects/DollOS-Server
grep -r "LearnerService\|learner" smolgura/ --include="*.py" | grep -v "learner.py"
grep -r "LearnerService\|learner" tests/ --include="*.py"
```

Remove any imports, instantiations, or IPC registrations for LearnerService (likely in a service manager or GuraOS main).

- [ ] **Step 2: Delete learner.py**

```bash
rm -f ~/Projects/DollOS-Server/smolgura/services/learner.py
```

- [ ] **Step 3: Check for ExperienceEntry model usage**

```bash
cd ~/Projects/DollOS-Server
grep -r "ExperienceEntry" smolgura/ --include="*.py"
```

If `ExperienceEntry` is in a models file (e.g., `services/models.py`), remove it. If the Tortoise ORM model is there, remove it and note that the DB table can be dropped in a migration.

- [ ] **Step 4: Commit**

```bash
cd ~/Projects/DollOS-Server
git add -A
git commit -m "refactor: remove LearnerService (TinyGura experience backflow)"
```

---

## Task 10: Remove Agent/Spawn Tools

**Goal:** Remove all TinyGura-related tool files and their registration in `load_all_tools()`.

**Files:**
- Delete: `~/Projects/DollOS-Server/smolgura/tools/agents/spawn_agent.py`
- Delete: `~/Projects/DollOS-Server/smolgura/tools/agents/list_processes.py`
- Delete: `~/Projects/DollOS-Server/smolgura/tools/agents/kill_process.py`
- Delete: `~/Projects/DollOS-Server/smolgura/tools/agents/create_agent.py`
- Delete: `~/Projects/DollOS-Server/smolgura/tools/agents/delete_agent.py`
- Delete: `~/Projects/DollOS-Server/smolgura/tools/agents/get_agent_info.py`
- Delete: `~/Projects/DollOS-Server/smolgura/tools/agents/list_agents.py`
- Delete: `~/Projects/DollOS-Server/smolgura/tools/agents/pc_agent.py`
- Delete: `~/Projects/DollOS-Server/smolgura/tools/agents/phone_agent.py`
- Edit: `~/Projects/DollOS-Server/smolgura/tools/__init__.py`
- Delete: `~/Projects/DollOS-Server/tests/test_tools/test_agent_tools.py`
- Delete: `~/Projects/DollOS-Server/tests/test_tools/test_phone_agent.py`
- Delete: `~/Projects/DollOS-Server/tests/test_tools/test_pc_agent.py`

- [ ] **Step 1: Delete agent tool files**

```bash
cd ~/Projects/DollOS-Server
rm -f smolgura/tools/agents/spawn_agent.py
rm -f smolgura/tools/agents/list_processes.py
rm -f smolgura/tools/agents/kill_process.py
rm -f smolgura/tools/agents/create_agent.py
rm -f smolgura/tools/agents/delete_agent.py
rm -f smolgura/tools/agents/get_agent_info.py
rm -f smolgura/tools/agents/list_agents.py
rm -f smolgura/tools/agents/pc_agent.py
rm -f smolgura/tools/agents/phone_agent.py
rm -f smolgura/tools/agents/__init__.py
rmdir smolgura/tools/agents/ 2>/dev/null || true
```

- [ ] **Step 2: Update `load_all_tools()` in `smolgura/tools/__init__.py`**

Edit `~/Projects/DollOS-Server/smolgura/tools/__init__.py` — remove all agent-related imports and entries from the returned list.

Remove these imports:
```python
from smolgura.tools.agents.create_agent import handler as create_agent_h
from smolgura.tools.agents.create_agent import tool as create_agent_t
from smolgura.tools.agents.delete_agent import handler as delete_agent_h
from smolgura.tools.agents.delete_agent import tool as delete_agent_t
from smolgura.tools.agents.get_agent_info import handler as get_agent_info_h
from smolgura.tools.agents.get_agent_info import tool as get_agent_info_t
from smolgura.tools.agents.kill_process import handler as kill_process_h
from smolgura.tools.agents.kill_process import tool as kill_process_t
from smolgura.tools.agents.list_agents import handler as list_agents_h
from smolgura.tools.agents.list_agents import tool as list_agents_t
from smolgura.tools.agents.list_processes import handler as list_processes_h
from smolgura.tools.agents.list_processes import tool as list_processes_t
from smolgura.tools.agents.pc_agent import handler as pc_agent_h
from smolgura.tools.agents.pc_agent import tool as pc_agent_t
from smolgura.tools.agents.phone_agent import handler as phone_agent_h
from smolgura.tools.agents.phone_agent import tool as phone_agent_t
from smolgura.tools.agents.spawn_agent import handler as spawn_agent_h
from smolgura.tools.agents.spawn_agent import tool as spawn_agent_t
```

Remove these entries from the returned list:
```python
(create_agent_t, create_agent_h),
(delete_agent_t, delete_agent_h),
(get_agent_info_t, get_agent_info_h),
(kill_process_t, kill_process_h),
(list_agents_t, list_agents_h),
(list_processes_t, list_processes_h),
(spawn_agent_t, spawn_agent_h),
(phone_agent_t, phone_agent_h),
(pc_agent_t, pc_agent_h),
```

Keep `researcher` — it's a web research tool, not a TinyGura spawn tool. Move it out of the `agents/` directory if that directory is being deleted:

```bash
# Keep researcher.py — move to tools/web/ or tools/
mv smolgura/tools/agents/researcher.py smolgura/tools/research/researcher.py 2>/dev/null || true
# Or if tools/agents/ dir needs to stay for researcher, just keep it
```

Actually, check if researcher depends on spawn:
```bash
grep -r "spawn\|TinyGura\|guraverse" smolgura/tools/agents/researcher.py
```

If researcher is independent, keep it. Update its import path in `load_all_tools()`.

- [ ] **Step 3: Delete agent test files**

```bash
rm -f tests/test_tools/test_agent_tools.py
rm -f tests/test_tools/test_phone_agent.py
rm -f tests/test_tools/test_pc_agent.py
```

- [ ] **Step 4: Verify**

```bash
cd ~/Projects/DollOS-Server
python -c "from smolgura.tools import load_all_tools; print(len(load_all_tools()), 'tools loaded')"
python -m pytest tests/test_tools/ -x
```

- [ ] **Step 5: Commit**

```bash
cd ~/Projects/DollOS-Server
git add -A
git commit -m "refactor: remove agent/spawn tools (TinyGura tools)"
```

---

## Task 11: Remove Deprecated Methods from GuraTools

**Goal:** Remove the now-unused `build_tool_env()`, `get_tool_signatures()`, and all Python signature constants. These were replaced by `build_tool_namespace()` in Task 4.

**Files:**
- Edit: `~/Projects/DollOS-Server/smolgura/gura/tools.py`

- [ ] **Step 1: Remove signature constants**

Remove from `~/Projects/DollOS-Server/smolgura/gura/tools.py`:
- `REMEMBER_SIGNATURE`
- `MEMORIZE_SIGNATURE`
- `ADD_TASK_SIGNATURE`
- `LEARN_SKILL_SIGNATURE`
- `RECALL_SKILLS_SIGNATURE`
- `REMOVE_TASK_SIGNATURE`
- `UPDATE_TASK_SIGNATURE`
- All `VISION_*_SIG` constants
- All `VIEWPOINT_*_SIG` constants
- All `IMAGE_*_SIG` constants
- `_fc_to_python_signature()` function
- `StackView` class (replaced by a `task.list` tool that returns JSON)

- [ ] **Step 2: Remove `build_tool_env()` and `get_tool_signatures()` methods**

Remove both methods from `GuraTools`. The only entry point is now `build_tool_namespace()`.

- [ ] **Step 3: Remove thread proxy utilities from executor.py**

These are already deleted with shell/ in Task 7. Verify no references remain.

- [ ] **Step 4: Update tests**

Update `~/Projects/DollOS-Server/tests/test_gura/test_tools.py` — remove tests for `build_tool_env()` and `get_tool_signatures()`. Keep only `TestBuildToolNamespace` and add more tests.

- [ ] **Step 5: Run full test suite**

```bash
cd ~/Projects/DollOS-Server
python -m pytest tests/test_gura/ -x
```

- [ ] **Step 6: Commit**

```bash
cd ~/Projects/DollOS-Server
git add smolgura/gura/tools.py tests/test_gura/test_tools.py
git commit -m "refactor: remove deprecated code execution methods from GuraTools"
```

---

## Task 12: Clean Up Remaining References

**Goal:** Find and fix all remaining references to removed code.

**Files:**
- Various files across the codebase

- [ ] **Step 1: Search for stale references**

```bash
cd ~/Projects/DollOS-Server
grep -r "RestrictedPython\|compile_restricted\|CodeExecutor\|code_exec\|CODE_EXEC" smolgura/ tests/ --include="*.py" | grep -v __pycache__
grep -r "ActionType\|add_code_result\|code_result\|CodeResult" smolgura/ tests/ --include="*.py" | grep -v __pycache__
grep -r "ShellPolicy\|shell\.policy\|shell\.guards\|shell\.executor\|shell\.transformer\|shell\.errors" smolgura/ tests/ --include="*.py" | grep -v __pycache__
grep -r "TinyGura\|tinygura\|GuraVerse\|guraverse\|spawn_agent\|AgentRegistry" smolgura/ tests/ --include="*.py" | grep -v __pycache__
grep -r "LearnerService\|ExperienceEntry\|experience_backflow" smolgura/ tests/ --include="*.py" | grep -v __pycache__
grep -r "_proxy_tools_to_loop\|_wrap_image_capture\|_make_proxy\|run_coroutine_threadsafe" smolgura/ tests/ --include="*.py" | grep -v __pycache__
grep -r "build_tool_env\|get_tool_signatures\|_fc_to_python_signature\|python_signature" smolgura/ tests/ --include="*.py" | grep -v __pycache__
```

- [ ] **Step 2: Fix each found reference**

For each file with stale references:
- Remove dead imports
- Update method calls to use new APIs
- Remove or rewrite tests that test removed functionality

- [ ] **Step 3: Remove RestrictedPython from dependencies**

Edit `~/Projects/DollOS-Server/pyproject.toml` (or `requirements.txt`):
- Remove `RestrictedPython` from dependencies

```bash
cd ~/Projects/DollOS-Server
grep -r "RestrictedPython" pyproject.toml requirements*.txt setup.py setup.cfg
# Remove the dependency line
```

- [ ] **Step 4: Full test suite**

```bash
cd ~/Projects/DollOS-Server
python -m pytest tests/ -x --timeout=30
```

- [ ] **Step 5: Commit**

```bash
cd ~/Projects/DollOS-Server
git add -A
git commit -m "chore: clean up all stale references to removed code"
```

---

## Task 13: Integration Test — Full Tool Calling Round Trip

**Goal:** Write an end-to-end test that verifies the complete flow: event -> GuraCore -> AgentLoop -> LLM (mocked) -> tool_call -> ToolNamespace dispatch -> tool_result -> LLM -> text response.

**Files:**
- Create: `~/Projects/DollOS-Server/tests/test_integration/test_tool_calling_e2e.py`

- [ ] **Step 1: Write integration test**

Create `~/Projects/DollOS-Server/tests/test_integration/test_tool_calling_e2e.py`:

```python
"""End-to-end test for native tool calling pipeline."""

from unittest.mock import AsyncMock, MagicMock

import pytest

from smolgura.gura.agent_loop import AgentContext, AgentLoop, StepResult
from smolgura.gura.tool_namespace import ToolNamespace


def _make_tool_call_response(tool_calls):
    msg = MagicMock()
    msg.content = None
    msg.reasoning_content = None
    tcs = []
    for tc in tool_calls:
        tc_obj = MagicMock()
        tc_obj.id = tc["id"]
        tc_obj.type = "function"
        tc_obj.function = MagicMock()
        tc_obj.function.name = tc["name"]
        tc_obj.function.arguments = tc["arguments"]
        tcs.append(tc_obj)
    msg.tool_calls = tcs
    choice = MagicMock()
    choice.message = msg
    choice.finish_reason = "tool_calls"
    resp = MagicMock()
    resp.choices = [choice]
    return resp


def _make_text_response(content):
    msg = MagicMock()
    msg.content = content
    msg.tool_calls = None
    msg.reasoning_content = None
    choice = MagicMock()
    choice.message = msg
    choice.finish_reason = "stop"
    resp = MagicMock()
    resp.choices = [choice]
    return resp


@pytest.mark.asyncio
async def test_full_round_trip():
    """LLM calls tool, gets result, then responds with text."""
    # Setup: LLM returns tool_call first, then text
    llm = AsyncMock()
    llm.chat = AsyncMock(
        side_effect=[
            _make_tool_call_response([{
                "id": "call_1",
                "name": "memory.remember",
                "arguments": '{"query": "user preferences"}',
            }]),
            _make_text_response("Based on your preferences, you like jazz music."),
        ]
    )

    # Setup tools
    ns = ToolNamespace()
    memories_db = [{"text": "User likes jazz music", "score": 0.95}]

    async def remember(query: str, limit: int = 5):
        return {"entries": memories_db[:limit]}

    ns.register(
        name="memory.remember",
        fn=remember,
        description="Search memories.",
        parameters={
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "limit": {"type": "integer", "default": 5},
            },
            "required": ["query"],
        },
    )

    ctx = AgentContext(agent_id="gura", system_prompt="You are Gura.", capabilities=["ALL"])
    loop = AgentLoop(llm=llm, context=ctx, tools=ns)
    loop.add_event("user", "What music do I like?")

    # Step 1: tool call
    result1 = await loop.step()
    assert not result1.done
    assert len(result1.tool_calls) == 1
    assert result1.tool_calls[0]["name"] == "memory.remember"
    assert result1.tool_calls[0]["result"]["entries"] == memories_db

    # Step 2: text response
    result2 = await loop.step()
    assert result2.done
    assert "jazz" in result2.response

    # Verify LLM received tool result in second call
    second_call_msgs = llm.chat.call_args_list[1].kwargs["messages"]
    tool_msgs = [m for m in second_call_msgs if m["role"] == "tool"]
    assert len(tool_msgs) == 1
    assert "jazz" in tool_msgs[0]["content"]


@pytest.mark.asyncio
async def test_multi_step_tool_chain():
    """LLM calls tools multiple times before final response."""
    call_count = 0

    async def mock_chat(**kwargs):
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            return _make_tool_call_response([{
                "id": "call_1", "name": "memory.remember",
                "arguments": '{"query": "schedule"}',
            }])
        elif call_count == 2:
            return _make_tool_call_response([{
                "id": "call_2", "name": "memory.memorize",
                "arguments": '{"text": "User asked about schedule"}',
            }])
        else:
            return _make_text_response("You have a meeting at 3pm.")

    llm = AsyncMock()
    llm.chat = AsyncMock(side_effect=mock_chat)

    ns = ToolNamespace()

    async def remember(query: str, limit: int = 5):
        return {"entries": [{"text": "Meeting at 3pm"}]}

    async def memorize(text: str, metadata: dict = {}):
        return {"stored": True}

    ns.register(name="memory.remember", fn=remember, description="Search", parameters={
        "type": "object", "properties": {"query": {"type": "string"}, "limit": {"type": "integer", "default": 5}},
        "required": ["query"],
    })
    ns.register(name="memory.memorize", fn=memorize, description="Store", parameters={
        "type": "object", "properties": {"text": {"type": "string"}, "metadata": {"type": "object", "default": {}}},
        "required": ["text"],
    })

    ctx = AgentContext(agent_id="gura", system_prompt="test", capabilities=[])
    loop = AgentLoop(llm=llm, context=ctx, tools=ns)
    loop.add_event("user", "What is my schedule?")

    # Run full loop
    for _ in range(10):
        result = await loop.step()
        if result.done:
            break

    assert result.done
    assert "3pm" in result.response
    assert call_count == 3
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_integration/test_tool_calling_e2e.py -x`

- [ ] **Step 2: Commit**

```bash
cd ~/Projects/DollOS-Server
git add tests/test_integration/test_tool_calling_e2e.py
git commit -m "test: add end-to-end integration tests for native tool calling"
```

---

## Summary of Changes

| Area | Before | After |
|------|--------|-------|
| Tool execution | `<code>await memory.remember(...)</code>` via RestrictedPython | LLM `tool_calls` -> `ToolNamespace.dispatch()` |
| AgentLoop | Regex code block detection + CodeResult | Parse `tool_calls` from response + tool results |
| ToolNamespace | Attribute-based (`ns.remember()`) with thread proxy | Registry with `to_tool_schemas()` + `dispatch()` |
| GuraTools | `build_tool_env()` + `get_tool_signatures()` | `build_tool_namespace()` |
| GuraCore._process | CodeExecutor + repeat detection + pending images | ToolNamespace + step loop |
| System prompt | `<code>...</code>` instructions | Tool calling instructions |
| shell/ | RestrictedPython sandbox | Removed |
| guraverse/ | TinyGura runtime + AgentRegistry | Removed |
| LearnerService | Experience backflow | Removed |
| Agent tools | spawn, list, kill, create, delete, pc_agent, phone_agent | Removed |
| LLM client | No tools parameter | `tools=` forwarded to API |
| Dependencies | RestrictedPython | Removed |
