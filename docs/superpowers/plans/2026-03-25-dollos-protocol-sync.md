# DollOS Protocol (Memory & Character Sync) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add memory sync and character sync to the existing phone driver WebSocket connection. Protocol v1 with versioned messages, file-based differential sync, conflict handling.

**Architecture:** Extend driver-phone with sync message types. SyncManager handles file manifest comparison, differential transfer, and conflict resolution. Memory sync on connect + real-time push on changes. Character sync bidirectional.

**Tech Stack:** Python, WebSocket, MessagePack, asyncio, watchfiles (file monitoring)

---

## Codebase Context

### Server Side (DollOS-Server)

The phone driver lives at `packages/driver-phone/src/driver_phone/`. Key files:

- `protocol.py` — `MessageType` enum (StrEnum) + `encode_message`/`decode_message` using msgpack
- `session.py` — `PhoneSession` (wraps WebSocket, has `send`/`request`/`resolve_pending`) + `SessionManager`
- `__init__.py` — `PhoneWebSocketServer` with `_handle_message` dispatch loop, NATS event publishing
- `tools.py` — Tool classes (ReplyTool, CaptureScreenTool, etc.) exposed via NATS ToolProvider

Current message format (no version field yet):
```python
{"type": "<msg_type>", "payload": {...}, "id": "<optional>"}
```

Driver registers via NATS: `system.driver.register` with capabilities `["event.phone.*", "system.driver.*"]`.

Memory storage on server: `/data/dollos/memory/<character-id>/` with `MEMORY.md`, `YYYY-MM-DD.md`, `people/`, `topics/`, `decisions/`, `index/`.

### Android Side (DollOSAIService)

- `MarkdownStore` — file I/O for three-tier Markdown memory (rootDir-relative paths: `MEMORY.md`, `daily/YYYY-MM-DD.md`, `people/*.md`, `topics/*.md`, `decisions/*.md`)
- `MemoryExporter` — ZIP-based export/import via `ParcelFileDescriptor` (zip-slip protection)
- `MemoryManager` — orchestrator with MemoryWriteQueue, EmbeddingProvider, MemorySearchEngine
- AIDL: `exportMemory(ParcelFileDescriptor)`, `importMemory(ParcelFileDescriptor)`, `importCharacter(ParcelFileDescriptor)`, `setActiveCharacter`, `listCharacters`

### Target Protocol v1 (from design spec Section 4)

Message envelope:
```
{"v": 1, "type": "<type>", "payload": {...}, "timestamp": <unix_ms>, "id": "<uuid>"}
```

New message types: `memory_sync_request`, `memory_sync_response`, `memory_file`, `character_sync`, `character_switch`, `status`.

---

## File Structure

### Server — New Files (under `packages/driver-phone/src/driver_phone/`)

```
sync/
  __init__.py
  manifest.py        — FileManifest: scan directory, compute SHA-256 + mtime per file
  engine.py          — SyncEngine: compare manifests, determine diffs, resolve conflicts
  memory.py          — MemorySyncManager: full sync on connect, real-time push with debounce
  character.py       — CharacterSyncManager: bidirectional .doll transfer
  watcher.py         — FileWatcher: watchfiles-based real-time change detection
  constants.py       — Protocol v1 constants, size limits, timeouts
```

### Server — Modified Files

```
protocol.py          — add v1 envelope, new MessageType entries, version negotiation
session.py           — add sync state to PhoneSession
__init__.py          — wire sync managers into PhoneWebSocketServer
pyproject.toml       — add watchfiles dependency
```

### Server — Test Files (under `packages/driver-phone/tests/`)

```
test_manifest.py     — FileManifest scan + hash correctness
test_engine.py       — SyncEngine diff + conflict resolution
test_protocol_v1.py  — Protocol v1 encode/decode, version negotiation
test_memory_sync.py  — MemorySyncManager integration
test_watcher.py      — FileWatcher debounce behaviour
```

---

## Task 1: Define Protocol v1 Message Types and Handshake

**Goal:** Upgrade the message envelope to include `v`, `timestamp`, `id` fields. Add version negotiation to the HELLO handshake. Add all sync-related message types. Maintain backward compatibility with existing messages.

**Files:**
- Modify: `packages/driver-phone/src/driver_phone/protocol.py`
- Modify: `packages/driver-phone/src/driver_phone/__init__.py` (HELLO handler)
- Create: `packages/driver-phone/src/driver_phone/sync/__init__.py`
- Create: `packages/driver-phone/src/driver_phone/sync/constants.py`
- Create: `packages/driver-phone/tests/test_protocol_v1.py`

- [ ] **Step 1: Add sync constants**

Create `packages/driver-phone/src/driver_phone/sync/__init__.py` (empty).

Create `packages/driver-phone/src/driver_phone/sync/constants.py`:

```python
"""DollOS Protocol v1 constants."""

PROTOCOL_VERSION = 1

# File size limits
MAX_FILE_SIZE = 1 * 1024 * 1024       # 1 MB per file
MAX_BATCH_SIZE = 50 * 1024 * 1024     # 50 MB per sync batch

# Timing
DEBOUNCE_SECONDS = 5.0                 # File watcher debounce
CONFLICT_THRESHOLD_SECONDS = 5.0       # mtime difference for "near-simultaneous"
CONFLICT_CLEANUP_DAYS = 7              # Auto-delete unmerged .conflict.md after N days

# File patterns
CONFLICT_SUFFIX = ".conflict.md"
MEMORY_EXTENSIONS = frozenset({".md"})
```

- [ ] **Step 2: Extend MessageType and message envelope**

Modify `packages/driver-phone/src/driver_phone/protocol.py`:

Add new message types to the `MessageType` enum:

```python
# Sync — bidirectional
MEMORY_SYNC_REQUEST = "memory_sync_request"
MEMORY_SYNC_RESPONSE = "memory_sync_response"
MEMORY_FILE = "memory_file"
CHARACTER_SYNC = "character_sync"
CHARACTER_SWITCH = "character_switch"
STATUS = "status"
```

Update `encode_message` to include `v`, `timestamp`, `id` fields:

```python
import time
import uuid

def encode_message(
    msg_type: MessageType,
    payload: dict,
    *,
    msg_id: str | None = None,
    version: int = 1,
) -> bytes:
    """Encode a message to MessagePack binary with protocol v1 envelope."""
    msg: dict = {
        "v": version,
        "type": str(msg_type),
        "payload": payload,
        "timestamp": int(time.time() * 1000),
        "id": msg_id or str(uuid.uuid4()),
    }
    return msgpack.packb(msg, use_bin_type=True)
```

Update `decode_message` to tolerate missing `v` field (backward compatibility with pre-v1 clients):

```python
def decode_message(data: bytes) -> dict:
    """Decode a MessagePack binary message.

    Validates that the result is a dict with a ``type`` field.
    Adds default v=0 for legacy messages without version field.
    """
    try:
        msg = msgpack.unpackb(data, raw=False)
    except (msgpack.UnpackValueError, msgpack.FormatError, msgpack.ExtraData) as e:
        raise ValueError(f"Failed to decode MessagePack message: {e}") from e
    if not isinstance(msg, dict) or "type" not in msg:
        raise ValueError("Message must be a dict with a 'type' field")
    msg.setdefault("v", 0)
    return msg
```

- [ ] **Step 3: Add version negotiation to HELLO handler**

In `__init__.py`, modify the `HELLO` handler in `_handle_message`:

```python
if msg_type == MessageType.HELLO:
    dev_id = payload.get("device_id")
    if not dev_id:
        logger.warning("hello message missing device_id")
        await ws.close(1008, "Missing device_id")
        return None
    # Protocol version negotiation
    client_version = msg.get("v", 0)
    if client_version < 1:
        logger.warning("client protocol version too old", version=client_version)
        # Still allow connection but disable sync features
    session = self._sessions.add(dev_id, ws)
    if session is None:
        await ws.close(1013, "Max connections reached")
        return None
    session.protocol_version = client_version
    await self._publish_event(dev_id, "connect", payload)
    logger.info("phone connected", device_id=dev_id, protocol_version=client_version)
    return dev_id
```

- [ ] **Step 4: Write protocol v1 tests**

Create `packages/driver-phone/tests/test_protocol_v1.py`:

Test cases:
- `test_encode_message_v1_envelope` — verify encoded message contains `v`, `timestamp`, `id` fields
- `test_decode_message_backward_compat` — verify decoding a message without `v` field sets `v=0`
- `test_decode_message_v1` — round-trip encode/decode with all v1 fields
- `test_new_message_types_exist` — verify all sync message types are defined in `MessageType`
- `test_encode_generates_uuid_if_no_id` — verify auto-generated UUID when msg_id not provided

```python
import time
import msgpack
import pytest
from driver_phone.protocol import MessageType, encode_message, decode_message

def test_encode_message_v1_envelope():
    raw = encode_message(MessageType.STATUS, {"state": "ok"})
    msg = msgpack.unpackb(raw, raw=False)
    assert msg["v"] == 1
    assert msg["type"] == "status"
    assert "timestamp" in msg
    assert "id" in msg
    assert abs(msg["timestamp"] - int(time.time() * 1000)) < 2000

def test_decode_message_backward_compat():
    legacy = msgpack.packb({"type": "hello", "payload": {}}, use_bin_type=True)
    msg = decode_message(legacy)
    assert msg["v"] == 0
    assert msg["type"] == "hello"

def test_round_trip_v1():
    raw = encode_message(MessageType.MEMORY_SYNC_REQUEST, {"manifest": []}, msg_id="test-123")
    msg = decode_message(raw)
    assert msg["v"] == 1
    assert msg["type"] == "memory_sync_request"
    assert msg["id"] == "test-123"
    assert msg["payload"]["manifest"] == []

def test_sync_message_types_exist():
    assert MessageType.MEMORY_SYNC_REQUEST == "memory_sync_request"
    assert MessageType.MEMORY_SYNC_RESPONSE == "memory_sync_response"
    assert MessageType.MEMORY_FILE == "memory_file"
    assert MessageType.CHARACTER_SYNC == "character_sync"
    assert MessageType.CHARACTER_SWITCH == "character_switch"
    assert MessageType.STATUS == "status"

def test_encode_generates_uuid_if_no_id():
    raw = encode_message(MessageType.STATUS, {})
    msg = msgpack.unpackb(raw, raw=False)
    assert len(msg["id"]) == 36  # UUID4 string length
```

Run: `cd packages/driver-phone && uv run pytest tests/test_protocol_v1.py -v`

Commit: `git commit -m "feat(driver-phone): add protocol v1 envelope, sync message types, and version negotiation"`

---

## Task 2: Create FileManifest

**Goal:** Build a manifest scanner that walks a directory of Markdown files and computes `{path, sha256, mtime}` for each file. This is the foundation for differential sync.

**Files:**
- Create: `packages/driver-phone/src/driver_phone/sync/manifest.py`
- Create: `packages/driver-phone/tests/test_manifest.py`

- [ ] **Step 1: Implement FileManifest**

Create `packages/driver-phone/src/driver_phone/sync/manifest.py`:

```python
"""File manifest for differential sync."""

from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass
from pathlib import Path

from driver_phone.sync.constants import MAX_FILE_SIZE, MEMORY_EXTENSIONS


@dataclass(frozen=True, slots=True)
class FileEntry:
    """A single file in the manifest."""

    path: str          # Relative path from memory root (forward slashes)
    sha256: str        # Hex digest
    mtime: float       # Unix timestamp (seconds, float)
    size: int          # Bytes


@dataclass
class FileManifest:
    """Snapshot of all memory files in a directory."""

    entries: dict[str, FileEntry]  # keyed by relative path

    @staticmethod
    def scan(root: Path, *, extensions: frozenset[str] = MEMORY_EXTENSIONS) -> FileManifest:
        """Scan a directory and build a manifest.

        Skips files exceeding MAX_FILE_SIZE. Skips the index/ directory
        (sqlite-vec databases are derived, not synced).
        """
        entries: dict[str, FileEntry] = {}
        root = root.resolve()

        if not root.is_dir():
            return FileManifest(entries={})

        for dirpath, dirnames, filenames in os.walk(root):
            # Skip index directory (derived data, not synced)
            dirnames[:] = [d for d in dirnames if d != "index"]

            for fname in filenames:
                if not any(fname.endswith(ext) for ext in extensions):
                    continue

                full_path = Path(dirpath) / fname
                rel_path = full_path.relative_to(root).as_posix()

                try:
                    stat = full_path.stat()
                except OSError:
                    continue

                if stat.st_size > MAX_FILE_SIZE:
                    continue

                sha = _sha256_file(full_path)
                entries[rel_path] = FileEntry(
                    path=rel_path,
                    sha256=sha,
                    mtime=stat.st_mtime,
                    size=stat.st_size,
                )

        return FileManifest(entries=entries)

    def to_wire(self) -> list[dict]:
        """Serialize for transmission over the wire."""
        return [
            {"path": e.path, "sha256": e.sha256, "mtime": e.mtime, "size": e.size}
            for e in self.entries.values()
        ]

    @staticmethod
    def from_wire(data: list[dict]) -> FileManifest:
        """Deserialize from wire format."""
        entries = {}
        for item in data:
            entry = FileEntry(
                path=item["path"],
                sha256=item["sha256"],
                mtime=item["mtime"],
                size=item["size"],
            )
            entries[entry.path] = entry
        return FileManifest(entries=entries)

    def total_size(self) -> int:
        """Total size of all files in bytes."""
        return sum(e.size for e in self.entries.values())


def _sha256_file(path: Path, *, chunk_size: int = 65536) -> str:
    """Compute SHA-256 hex digest of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()
```

- [ ] **Step 2: Write manifest tests**

Create `packages/driver-phone/tests/test_manifest.py`:

```python
import hashlib
from pathlib import Path

import pytest

from driver_phone.sync.manifest import FileManifest, FileEntry


@pytest.fixture
def memory_tree(tmp_path: Path) -> Path:
    """Create a realistic memory directory structure."""
    root = tmp_path / "memory" / "gura"
    root.mkdir(parents=True)

    (root / "MEMORY.md").write_text("# Core memory\nMaster likes cats.")
    (root / "2026-03-25.md").write_text("# 2026-03-25\nWent to the park.")

    (root / "people").mkdir()
    (root / "people" / "master.md").write_text("# Master\nKind person.")

    (root / "topics").mkdir()
    (root / "topics" / "music.md").write_text("# Music\nLikes jazz.")

    # index/ should be skipped
    (root / "index").mkdir()
    (root / "index" / "memory.db").write_bytes(b"\x00" * 100)

    # Non-md file should be skipped
    (root / "notes.txt").write_text("not a markdown file")

    return root


def test_scan_finds_all_md_files(memory_tree: Path):
    manifest = FileManifest.scan(memory_tree)
    paths = set(manifest.entries.keys())
    assert paths == {
        "MEMORY.md",
        "2026-03-25.md",
        "people/master.md",
        "topics/music.md",
    }


def test_scan_skips_index_dir(memory_tree: Path):
    manifest = FileManifest.scan(memory_tree)
    assert not any("index" in p for p in manifest.entries)


def test_scan_skips_non_md(memory_tree: Path):
    manifest = FileManifest.scan(memory_tree)
    assert "notes.txt" not in manifest.entries


def test_sha256_correctness(memory_tree: Path):
    manifest = FileManifest.scan(memory_tree)
    entry = manifest.entries["MEMORY.md"]
    expected = hashlib.sha256(b"# Core memory\nMaster likes cats.").hexdigest()
    assert entry.sha256 == expected


def test_scan_skips_oversized_files(tmp_path: Path):
    root = tmp_path / "big"
    root.mkdir()
    big_file = root / "huge.md"
    big_file.write_bytes(b"x" * (1024 * 1024 + 1))  # 1 byte over limit
    manifest = FileManifest.scan(root)
    assert "huge.md" not in manifest.entries


def test_round_trip_wire_format(memory_tree: Path):
    original = FileManifest.scan(memory_tree)
    wire = original.to_wire()
    restored = FileManifest.from_wire(wire)
    assert set(restored.entries.keys()) == set(original.entries.keys())
    for path, entry in original.entries.items():
        assert restored.entries[path].sha256 == entry.sha256
        assert restored.entries[path].mtime == entry.mtime


def test_scan_empty_dir(tmp_path: Path):
    manifest = FileManifest.scan(tmp_path)
    assert manifest.entries == {}


def test_scan_nonexistent_dir(tmp_path: Path):
    manifest = FileManifest.scan(tmp_path / "does_not_exist")
    assert manifest.entries == {}
```

Run: `cd packages/driver-phone && uv run pytest tests/test_manifest.py -v`

Commit: `git commit -m "feat(driver-phone): add FileManifest for directory scanning and SHA-256 hashing"`

---

## Task 3: Create SyncEngine

**Goal:** Compare two manifests and produce a list of actions (send, receive, skip, conflict). Implement last-write-wins conflict resolution with `.conflict.md` for near-simultaneous edits.

**Files:**
- Create: `packages/driver-phone/src/driver_phone/sync/engine.py`
- Create: `packages/driver-phone/tests/test_engine.py`

- [ ] **Step 1: Implement SyncEngine**

Create `packages/driver-phone/src/driver_phone/sync/engine.py`:

```python
"""Sync engine: manifest comparison and conflict resolution."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

import structlog

from driver_phone.sync.constants import CONFLICT_SUFFIX, CONFLICT_THRESHOLD_SECONDS
from driver_phone.sync.manifest import FileEntry, FileManifest

logger = structlog.get_logger()


class SyncAction(StrEnum):
    """What to do with a file during sync."""

    SEND = "send"          # Local is newer or only exists locally → send to remote
    RECEIVE = "receive"    # Remote is newer or only exists remotely → receive from remote
    CONFLICT = "conflict"  # Near-simultaneous edit → keep both
    SKIP = "skip"          # Identical on both sides


@dataclass(frozen=True, slots=True)
class SyncItem:
    """A single file sync decision."""

    path: str
    action: SyncAction
    local_entry: FileEntry | None = None
    remote_entry: FileEntry | None = None


class SyncEngine:
    """Compares local and remote manifests to produce sync actions."""

    @staticmethod
    def diff(local: FileManifest, remote: FileManifest) -> list[SyncItem]:
        """Compare two manifests and return sync actions.

        Rules:
        - Same hash → SKIP
        - Only exists locally → SEND
        - Only exists remotely → RECEIVE
        - Both exist, different hash:
          - mtime diff > CONFLICT_THRESHOLD_SECONDS → last-write-wins (newer side wins)
          - mtime diff <= CONFLICT_THRESHOLD_SECONDS → CONFLICT (keep both)
        """
        all_paths = set(local.entries.keys()) | set(remote.entries.keys())
        items: list[SyncItem] = []

        for path in sorted(all_paths):
            local_entry = local.entries.get(path)
            remote_entry = remote.entries.get(path)

            if local_entry and not remote_entry:
                items.append(SyncItem(path=path, action=SyncAction.SEND, local_entry=local_entry))
            elif remote_entry and not local_entry:
                items.append(SyncItem(path=path, action=SyncAction.RECEIVE, remote_entry=remote_entry))
            elif local_entry and remote_entry:
                if local_entry.sha256 == remote_entry.sha256:
                    items.append(SyncItem(
                        path=path, action=SyncAction.SKIP,
                        local_entry=local_entry, remote_entry=remote_entry,
                    ))
                else:
                    mtime_diff = abs(local_entry.mtime - remote_entry.mtime)
                    if mtime_diff <= CONFLICT_THRESHOLD_SECONDS:
                        items.append(SyncItem(
                            path=path, action=SyncAction.CONFLICT,
                            local_entry=local_entry, remote_entry=remote_entry,
                        ))
                    elif local_entry.mtime > remote_entry.mtime:
                        items.append(SyncItem(
                            path=path, action=SyncAction.SEND,
                            local_entry=local_entry, remote_entry=remote_entry,
                        ))
                    else:
                        items.append(SyncItem(
                            path=path, action=SyncAction.RECEIVE,
                            local_entry=local_entry, remote_entry=remote_entry,
                        ))

        return items

    @staticmethod
    def apply_conflict(root: Path, path: str, remote_content: bytes) -> str:
        """Write a .conflict.md file for a near-simultaneous edit.

        Returns the conflict file's relative path.
        """
        if path.endswith(".md"):
            conflict_path = path[:-3] + CONFLICT_SUFFIX
        else:
            conflict_path = path + CONFLICT_SUFFIX

        dest = root / conflict_path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(remote_content)
        logger.info("conflict file created", path=conflict_path)
        return conflict_path

    @staticmethod
    def cleanup_conflicts(root: Path, *, max_age_days: int = 7) -> list[str]:
        """Delete .conflict.md files older than max_age_days.

        Returns list of deleted relative paths.
        """
        import time

        cutoff = time.time() - (max_age_days * 86400)
        deleted: list[str] = []

        for conflict_file in root.rglob(f"*{CONFLICT_SUFFIX}"):
            try:
                if conflict_file.stat().st_mtime < cutoff:
                    rel = conflict_file.relative_to(root).as_posix()
                    conflict_file.unlink()
                    deleted.append(rel)
                    logger.info("expired conflict file removed", path=rel)
            except OSError:
                continue

        return deleted
```

- [ ] **Step 2: Write sync engine tests**

Create `packages/driver-phone/tests/test_engine.py`:

```python
import time
from pathlib import Path

import pytest

from driver_phone.sync.engine import SyncAction, SyncEngine, SyncItem
from driver_phone.sync.manifest import FileEntry, FileManifest


def _entry(path: str, sha: str = "abc", mtime: float = 1000.0, size: int = 100) -> FileEntry:
    return FileEntry(path=path, sha256=sha, mtime=mtime, size=size)


def _manifest(*entries: FileEntry) -> FileManifest:
    return FileManifest(entries={e.path: e for e in entries})


class TestSyncEngineDiff:
    def test_identical_manifests_all_skip(self):
        e = _entry("MEMORY.md", sha="aaa", mtime=1000)
        items = SyncEngine.diff(_manifest(e), _manifest(e))
        assert len(items) == 1
        assert items[0].action == SyncAction.SKIP

    def test_local_only_sends(self):
        local = _manifest(_entry("new.md"))
        remote = _manifest()
        items = SyncEngine.diff(local, remote)
        assert len(items) == 1
        assert items[0].action == SyncAction.SEND
        assert items[0].path == "new.md"

    def test_remote_only_receives(self):
        local = _manifest()
        remote = _manifest(_entry("remote.md"))
        items = SyncEngine.diff(local, remote)
        assert len(items) == 1
        assert items[0].action == SyncAction.RECEIVE

    def test_different_hash_local_newer_sends(self):
        local = _manifest(_entry("f.md", sha="aaa", mtime=2000))
        remote = _manifest(_entry("f.md", sha="bbb", mtime=1000))
        items = SyncEngine.diff(local, remote)
        assert items[0].action == SyncAction.SEND

    def test_different_hash_remote_newer_receives(self):
        local = _manifest(_entry("f.md", sha="aaa", mtime=1000))
        remote = _manifest(_entry("f.md", sha="bbb", mtime=2000))
        items = SyncEngine.diff(local, remote)
        assert items[0].action == SyncAction.RECEIVE

    def test_near_simultaneous_conflict(self):
        local = _manifest(_entry("f.md", sha="aaa", mtime=1000.0))
        remote = _manifest(_entry("f.md", sha="bbb", mtime=1003.0))  # 3s diff < 5s threshold
        items = SyncEngine.diff(local, remote)
        assert items[0].action == SyncAction.CONFLICT

    def test_exactly_at_threshold_is_conflict(self):
        local = _manifest(_entry("f.md", sha="aaa", mtime=1000.0))
        remote = _manifest(_entry("f.md", sha="bbb", mtime=1005.0))  # exactly 5s
        items = SyncEngine.diff(local, remote)
        assert items[0].action == SyncAction.CONFLICT

    def test_mixed_scenario(self):
        local = _manifest(
            _entry("same.md", sha="x", mtime=100),
            _entry("local_only.md", sha="y", mtime=100),
            _entry("changed.md", sha="old", mtime=100),
        )
        remote = _manifest(
            _entry("same.md", sha="x", mtime=100),
            _entry("remote_only.md", sha="z", mtime=100),
            _entry("changed.md", sha="new", mtime=200),
        )
        items = SyncEngine.diff(local, remote)
        actions = {item.path: item.action for item in items}
        assert actions["same.md"] == SyncAction.SKIP
        assert actions["local_only.md"] == SyncAction.SEND
        assert actions["remote_only.md"] == SyncAction.RECEIVE
        assert actions["changed.md"] == SyncAction.RECEIVE

    def test_results_sorted_by_path(self):
        local = _manifest(_entry("z.md"), _entry("a.md"), _entry("m.md"))
        items = SyncEngine.diff(local, _manifest())
        assert [i.path for i in items] == ["a.md", "m.md", "z.md"]


class TestConflictHandling:
    def test_apply_conflict_creates_file(self, tmp_path: Path):
        conflict_path = SyncEngine.apply_conflict(tmp_path, "people/master.md", b"remote version")
        assert conflict_path == "people/master.conflict.md"
        assert (tmp_path / "people" / "master.conflict.md").read_bytes() == b"remote version"

    def test_cleanup_removes_old_conflicts(self, tmp_path: Path):
        old = tmp_path / "old.conflict.md"
        old.write_text("stale")
        # Set mtime to 10 days ago
        import os
        old_time = time.time() - (10 * 86400)
        os.utime(old, (old_time, old_time))

        recent = tmp_path / "recent.conflict.md"
        recent.write_text("fresh")

        deleted = SyncEngine.cleanup_conflicts(tmp_path, max_age_days=7)
        assert "old.conflict.md" in deleted
        assert not old.exists()
        assert recent.exists()

    def test_cleanup_empty_dir(self, tmp_path: Path):
        deleted = SyncEngine.cleanup_conflicts(tmp_path)
        assert deleted == []
```

Run: `cd packages/driver-phone && uv run pytest tests/test_engine.py -v`

Commit: `git commit -m "feat(driver-phone): add SyncEngine with manifest diffing and conflict resolution"`

---

## Task 4: Create MemorySyncManager

**Goal:** Orchestrate full sync on connect and real-time push on file changes. Uses FileManifest and SyncEngine internally. Debounces file changes with 5-second window.

**Files:**
- Create: `packages/driver-phone/src/driver_phone/sync/memory.py`
- Create: `packages/driver-phone/tests/test_memory_sync.py`

- [ ] **Step 1: Implement MemorySyncManager**

Create `packages/driver-phone/src/driver_phone/sync/memory.py`:

```python
"""Memory sync manager: full sync on connect, real-time push on changes."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import TYPE_CHECKING

import structlog

from driver_phone.protocol import MessageType
from driver_phone.sync.constants import MAX_BATCH_SIZE, MAX_FILE_SIZE
from driver_phone.sync.engine import SyncAction, SyncEngine
from driver_phone.sync.manifest import FileManifest

if TYPE_CHECKING:
    from driver_phone.session import PhoneSession

logger = structlog.get_logger()


class MemorySyncManager:
    """Handles memory sync for a single session.

    Lifecycle:
    1. On connect → full_sync() exchanges manifests and transfers diffs
    2. On local file change → push_changes() sends modified files
    3. On incoming memory_sync_request → handle_sync_request() responds
    4. On incoming memory_file → handle_memory_file() writes to disk
    """

    def __init__(self, memory_root: Path, character_id: str) -> None:
        self._root = memory_root / character_id
        self._character_id = character_id
        self._root.mkdir(parents=True, exist_ok=True)

    @property
    def memory_dir(self) -> Path:
        return self._root

    def switch_character(self, character_id: str) -> None:
        """Switch to a different character's memory directory."""
        self._character_id = character_id
        self._root = self._root.parent / character_id
        self._root.mkdir(parents=True, exist_ok=True)

    # ── Full sync (on connect) ─────────────────────────────────────────

    async def full_sync(self, session: PhoneSession) -> None:
        """Initiate a full sync by sending our manifest to the remote."""
        manifest = await asyncio.get_running_loop().run_in_executor(
            None, lambda: FileManifest.scan(self._root)
        )
        logger.info(
            "initiating full sync",
            character_id=self._character_id,
            file_count=len(manifest.entries),
        )
        await session.send(
            MessageType.MEMORY_SYNC_REQUEST,
            {"character_id": self._character_id, "manifest": manifest.to_wire()},
        )

    # ── Handle incoming sync request ───────────────────────────────────

    async def handle_sync_request(
        self, session: PhoneSession, payload: dict
    ) -> None:
        """Handle a memory_sync_request from the remote.

        1. Build local manifest
        2. Compare with remote manifest
        3. Send files the remote needs (SEND actions)
        4. Request files we need (RECEIVE actions)
        5. Handle conflicts
        """
        remote_manifest = FileManifest.from_wire(payload.get("manifest", []))
        local_manifest = await asyncio.get_running_loop().run_in_executor(
            None, lambda: FileManifest.scan(self._root)
        )

        items = SyncEngine.diff(local_manifest, remote_manifest)

        to_send: list[str] = []
        to_request: list[str] = []
        conflicts: list[str] = []

        for item in items:
            if item.action == SyncAction.SEND:
                to_send.append(item.path)
            elif item.action == SyncAction.RECEIVE:
                to_request.append(item.path)
            elif item.action == SyncAction.CONFLICT:
                conflicts.append(item.path)
                to_request.append(item.path)  # We'll save as .conflict.md

        logger.info(
            "sync diff computed",
            send=len(to_send),
            receive=len(to_request),
            conflicts=len(conflicts),
            skip=sum(1 for i in items if i.action == SyncAction.SKIP),
        )

        # Send memory_sync_response telling remote which files we need
        await session.send(
            MessageType.MEMORY_SYNC_RESPONSE,
            {
                "character_id": self._character_id,
                "need_files": to_request,
                "conflict_files": conflicts,
            },
        )

        # Send files the remote needs
        batch_size = 0
        for path in to_send:
            file_path = self._root / path
            if not file_path.is_file():
                continue
            content = await asyncio.get_running_loop().run_in_executor(
                None, file_path.read_bytes
            )
            if len(content) > MAX_FILE_SIZE:
                logger.warning("skipping oversized file", path=path, size=len(content))
                continue
            batch_size += len(content)
            if batch_size > MAX_BATCH_SIZE:
                logger.warning("batch size limit reached, stopping send", sent_so_far=batch_size)
                break
            await session.send(
                MessageType.MEMORY_FILE,
                {
                    "character_id": self._character_id,
                    "path": path,
                    "content": content,
                    "mtime": file_path.stat().st_mtime,
                },
            )

    # ── Handle incoming sync response ──────────────────────────────────

    async def handle_sync_response(
        self, session: PhoneSession, payload: dict
    ) -> None:
        """Handle a memory_sync_response: send the files the remote requested."""
        need_files: list[str] = payload.get("need_files", [])
        batch_size = 0

        for path in need_files:
            file_path = self._root / path
            if not file_path.is_file():
                continue
            content = await asyncio.get_running_loop().run_in_executor(
                None, file_path.read_bytes
            )
            if len(content) > MAX_FILE_SIZE:
                continue
            batch_size += len(content)
            if batch_size > MAX_BATCH_SIZE:
                logger.warning("batch limit reached in response", sent=batch_size)
                break
            await session.send(
                MessageType.MEMORY_FILE,
                {
                    "character_id": self._character_id,
                    "path": path,
                    "content": content,
                    "mtime": file_path.stat().st_mtime,
                },
            )

    # ── Handle incoming memory file ────────────────────────────────────

    async def handle_memory_file(self, payload: dict, *, is_conflict: bool = False) -> None:
        """Write a received memory file to disk.

        If is_conflict is True, saves as .conflict.md instead of overwriting.
        """
        path = payload.get("path", "")
        content = payload.get("content", b"")
        mtime = payload.get("mtime")

        if not path:
            logger.warning("memory_file missing path")
            return

        if isinstance(content, str):
            content = content.encode("utf-8")

        if len(content) > MAX_FILE_SIZE:
            logger.warning("rejecting oversized memory_file", path=path, size=len(content))
            return

        # Zip-slip protection
        dest = (self._root / path).resolve()
        if not str(dest).startswith(str(self._root.resolve())):
            logger.warning("path traversal blocked", path=path)
            return

        if is_conflict:
            SyncEngine.apply_conflict(self._root, path, content)
        else:
            await asyncio.get_running_loop().run_in_executor(
                None, self._write_file, dest, content, mtime
            )
            logger.info("memory file written", path=path)

    def _write_file(self, dest: Path, content: bytes, mtime: float | None) -> None:
        """Write file content and optionally set mtime."""
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(content)
        if mtime:
            import os
            os.utime(dest, (mtime, mtime))

    # ── Push single file change (real-time) ────────────────────────────

    async def push_file(self, session: PhoneSession, relative_path: str) -> None:
        """Push a single changed file to the remote."""
        file_path = self._root / relative_path
        if not file_path.is_file():
            logger.warning("push_file: file does not exist", path=relative_path)
            return
        content = await asyncio.get_running_loop().run_in_executor(
            None, file_path.read_bytes
        )
        if len(content) > MAX_FILE_SIZE:
            logger.warning("push_file: oversized", path=relative_path, size=len(content))
            return
        await session.send(
            MessageType.MEMORY_FILE,
            {
                "character_id": self._character_id,
                "path": relative_path,
                "content": content,
                "mtime": file_path.stat().st_mtime,
            },
        )
        logger.info("pushed file change", path=relative_path)
```

- [ ] **Step 2: Write MemorySyncManager tests**

Create `packages/driver-phone/tests/test_memory_sync.py`:

```python
import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

from driver_phone.protocol import MessageType
from driver_phone.sync.memory import MemorySyncManager


@pytest.fixture
def memory_root(tmp_path: Path) -> Path:
    root = tmp_path / "memory"
    root.mkdir()
    return root


@pytest.fixture
def manager(memory_root: Path) -> MemorySyncManager:
    return MemorySyncManager(memory_root, "gura")


@pytest.fixture
def mock_session() -> AsyncMock:
    session = AsyncMock()
    session.send = AsyncMock()
    return session


class TestFullSync:
    @pytest.mark.asyncio
    async def test_sends_manifest_on_full_sync(self, manager, mock_session):
        # Create a file
        (manager.memory_dir / "MEMORY.md").write_text("hello")
        await manager.full_sync(mock_session)

        mock_session.send.assert_called_once()
        call_args = mock_session.send.call_args
        assert call_args[0][0] == MessageType.MEMORY_SYNC_REQUEST
        payload = call_args[0][1]
        assert payload["character_id"] == "gura"
        assert len(payload["manifest"]) == 1
        assert payload["manifest"][0]["path"] == "MEMORY.md"


class TestHandleSyncRequest:
    @pytest.mark.asyncio
    async def test_sends_local_only_files(self, manager, mock_session):
        (manager.memory_dir / "local.md").write_text("local only")

        await manager.handle_sync_request(mock_session, {"manifest": []})

        # Should send: sync_response + the file itself
        calls = mock_session.send.call_args_list
        assert len(calls) == 2
        # First call: memory_sync_response
        assert calls[0][0][0] == MessageType.MEMORY_SYNC_RESPONSE
        # Second call: memory_file
        assert calls[1][0][0] == MessageType.MEMORY_FILE
        assert calls[1][0][1]["path"] == "local.md"


class TestHandleMemoryFile:
    @pytest.mark.asyncio
    async def test_writes_file_to_disk(self, manager):
        await manager.handle_memory_file({
            "path": "people/friend.md",
            "content": b"# Friend\nNice person.",
            "mtime": 1000.0,
        })
        assert (manager.memory_dir / "people" / "friend.md").read_text() == "# Friend\nNice person."

    @pytest.mark.asyncio
    async def test_blocks_path_traversal(self, manager):
        await manager.handle_memory_file({
            "path": "../../etc/passwd",
            "content": b"evil",
            "mtime": 1000.0,
        })
        assert not Path("/etc/passwd_test").exists()

    @pytest.mark.asyncio
    async def test_rejects_oversized_file(self, manager):
        await manager.handle_memory_file({
            "path": "huge.md",
            "content": b"x" * (1024 * 1024 + 1),
            "mtime": 1000.0,
        })
        assert not (manager.memory_dir / "huge.md").exists()

    @pytest.mark.asyncio
    async def test_conflict_creates_conflict_file(self, manager):
        (manager.memory_dir / "test.md").write_text("local version")
        await manager.handle_memory_file(
            {"path": "test.md", "content": b"remote version", "mtime": 1000.0},
            is_conflict=True,
        )
        assert (manager.memory_dir / "test.conflict.md").read_text() == "remote version"
        assert (manager.memory_dir / "test.md").read_text() == "local version"


class TestSwitchCharacter:
    def test_switches_memory_dir(self, manager, memory_root):
        manager.switch_character("luna")
        assert manager.memory_dir == memory_root / "luna"
        assert manager.memory_dir.is_dir()


class TestPushFile:
    @pytest.mark.asyncio
    async def test_pushes_existing_file(self, manager, mock_session):
        (manager.memory_dir / "test.md").write_text("content")
        await manager.push_file(mock_session, "test.md")
        mock_session.send.assert_called_once()
        payload = mock_session.send.call_args[0][1]
        assert payload["path"] == "test.md"
        assert payload["content"] == b"content"

    @pytest.mark.asyncio
    async def test_push_nonexistent_file_warns(self, manager, mock_session):
        await manager.push_file(mock_session, "nope.md")
        mock_session.send.assert_not_called()
```

Run: `cd packages/driver-phone && uv run pytest tests/test_memory_sync.py -v`

Commit: `git commit -m "feat(driver-phone): add MemorySyncManager with full sync, file transfer, and conflict handling"`

---

## Task 5: Add Memory Sync Handlers to driver-phone

**Goal:** Wire the MemorySyncManager into `PhoneWebSocketServer._handle_message` so that incoming `memory_sync_request`, `memory_sync_response`, and `memory_file` messages are dispatched correctly. Trigger full sync after HELLO.

**Files:**
- Modify: `packages/driver-phone/src/driver_phone/__init__.py`
- Modify: `packages/driver-phone/src/driver_phone/session.py`

- [ ] **Step 1: Add sync state to PhoneSession**

In `packages/driver-phone/src/driver_phone/session.py`, add to the `PhoneSession` dataclass:

```python
protocol_version: int = 0
character_id: str = ""
```

These fields track the negotiated protocol version and the active character for sync.

- [ ] **Step 2: Integrate MemorySyncManager into PhoneWebSocketServer**

In `packages/driver-phone/src/driver_phone/__init__.py`:

Add to `PhoneWebSocketServer.__init__`:

```python
from driver_phone.sync.memory import MemorySyncManager

# Memory root from config: /data/dollos/memory/
self._memory_root = Path(config.data_dir) / "memory"
self._sync_managers: dict[str, MemorySyncManager] = {}  # keyed by device_id
```

Add a helper to get/create a sync manager for a device:

```python
def _get_sync_manager(self, device_id: str, character_id: str) -> MemorySyncManager:
    if device_id not in self._sync_managers:
        self._sync_managers[device_id] = MemorySyncManager(self._memory_root, character_id)
    return self._sync_managers[device_id]
```

Add these handlers in `_handle_message`, after the existing message type checks, before the "unknown message type" warning:

```python
if msg_type == MessageType.MEMORY_SYNC_REQUEST and device_id:
    char_id = payload.get("character_id", "")
    mgr = self._get_sync_manager(device_id, char_id)
    asyncio.create_task(mgr.handle_sync_request(
        self._sessions.get(device_id), payload
    ))
    return device_id

if msg_type == MessageType.MEMORY_SYNC_RESPONSE and device_id:
    char_id = payload.get("character_id", "")
    mgr = self._get_sync_manager(device_id, char_id)
    asyncio.create_task(mgr.handle_sync_response(
        self._sessions.get(device_id), payload
    ))
    return device_id

if msg_type == MessageType.MEMORY_FILE and device_id:
    char_id = payload.get("character_id", "")
    mgr = self._get_sync_manager(device_id, char_id)
    conflict_files = getattr(mgr, '_pending_conflicts', set())
    is_conflict = payload.get("path", "") in conflict_files
    asyncio.create_task(mgr.handle_memory_file(payload, is_conflict=is_conflict))
    return device_id
```

In the HELLO handler, after the session is added and if `protocol_version >= 1`, trigger full sync:

```python
if client_version >= 1:
    char_id = payload.get("character_id", "default")
    session.character_id = char_id
    mgr = self._get_sync_manager(dev_id, char_id)
    asyncio.create_task(mgr.full_sync(session))
```

In the `finally` block of `_handler`, clean up the sync manager:

```python
self._sync_managers.pop(device_id, None)
```

- [ ] **Step 3: Verify integration**

Run: `cd packages/driver-phone && uv run pytest tests/ -v`

Commit: `git commit -m "feat(driver-phone): wire MemorySyncManager into WebSocket message handler"`

---

## Task 6: Add File Watcher for Real-Time Sync

**Goal:** Watch the memory directory for changes and push modified files to connected phones with 5-second debounce.

**Files:**
- Create: `packages/driver-phone/src/driver_phone/sync/watcher.py`
- Create: `packages/driver-phone/tests/test_watcher.py`
- Modify: `packages/driver-phone/pyproject.toml` (add `watchfiles` dependency)

- [ ] **Step 1: Add watchfiles dependency**

In `packages/driver-phone/pyproject.toml`, add to dependencies:

```toml
"watchfiles>=1.0",
```

- [ ] **Step 2: Implement FileWatcher**

Create `packages/driver-phone/src/driver_phone/sync/watcher.py`:

```python
"""File watcher for real-time memory sync with debounce."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import TYPE_CHECKING, Callable, Awaitable

import structlog

if TYPE_CHECKING:
    pass

logger = structlog.get_logger()


class FileWatcher:
    """Watches a directory for Markdown file changes with debounce.

    Uses watchfiles for efficient OS-level file monitoring.
    Debounces changes by DEBOUNCE_SECONDS — accumulates all changed paths
    within the window, then fires the callback once with the batch.
    """

    def __init__(
        self,
        root: Path,
        *,
        debounce_seconds: float = 5.0,
        on_changes: Callable[[set[str]], Awaitable[None]] | None = None,
    ) -> None:
        self._root = root.resolve()
        self._debounce = debounce_seconds
        self._on_changes = on_changes
        self._task: asyncio.Task | None = None
        self._pending: set[str] = set()
        self._debounce_task: asyncio.Task | None = None

    async def start(self) -> None:
        """Start watching the directory."""
        if self._task is not None:
            return
        self._task = asyncio.create_task(self._watch_loop())
        logger.info("file watcher started", root=str(self._root))

    async def stop(self) -> None:
        """Stop the watcher."""
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        if self._debounce_task:
            self._debounce_task.cancel()
            try:
                await self._debounce_task
            except asyncio.CancelledError:
                pass
            self._debounce_task = None
        logger.info("file watcher stopped")

    async def _watch_loop(self) -> None:
        """Main watch loop using watchfiles."""
        from watchfiles import awatch, Change

        try:
            async for changes in awatch(self._root, step=1000):
                for change_type, path_str in changes:
                    path = Path(path_str)

                    # Only track Markdown files
                    if path.suffix != ".md":
                        continue

                    # Skip index/ directory
                    try:
                        rel = path.relative_to(self._root)
                    except ValueError:
                        continue
                    if rel.parts and rel.parts[0] == "index":
                        continue

                    rel_posix = rel.as_posix()
                    self._pending.add(rel_posix)
                    logger.debug("file change detected", path=rel_posix, change=str(change_type))

                # Reset debounce timer
                if self._pending:
                    if self._debounce_task and not self._debounce_task.done():
                        self._debounce_task.cancel()
                    self._debounce_task = asyncio.create_task(self._debounce_flush())
        except asyncio.CancelledError:
            raise

    async def _debounce_flush(self) -> None:
        """Wait for debounce period, then flush pending changes."""
        await asyncio.sleep(self._debounce)
        if self._pending and self._on_changes:
            batch = self._pending.copy()
            self._pending.clear()
            logger.info("flushing file changes", count=len(batch))
            try:
                await self._on_changes(batch)
            except Exception:
                logger.exception("error in file change callback")
```

- [ ] **Step 3: Wire watcher into PhoneWebSocketServer**

In `packages/driver-phone/src/driver_phone/__init__.py`, add watcher setup:

After creating the sync manager in the HELLO handler, create and start a watcher:

```python
from driver_phone.sync.watcher import FileWatcher

# In __init__:
self._watchers: dict[str, FileWatcher] = {}  # keyed by device_id

# After full_sync in HELLO handler:
async def _on_file_changes(changed_paths: set[str], dev_id=dev_id, char_id=char_id):
    session = self._sessions.get(dev_id)
    if not session or not session.is_alive:
        return
    mgr = self._sync_managers.get(dev_id)
    if mgr:
        for path in changed_paths:
            await mgr.push_file(session, path)

watcher = FileWatcher(
    mgr.memory_dir,
    on_changes=_on_file_changes,
)
self._watchers[dev_id] = watcher
asyncio.create_task(watcher.start())

# In _handler finally block, also stop the watcher:
watcher = self._watchers.pop(device_id, None)
if watcher:
    asyncio.create_task(watcher.stop())
```

- [ ] **Step 4: Write watcher tests**

Create `packages/driver-phone/tests/test_watcher.py`:

```python
import asyncio
from pathlib import Path

import pytest

from driver_phone.sync.watcher import FileWatcher


@pytest.mark.asyncio
async def test_watcher_detects_changes(tmp_path: Path):
    """Watcher should detect new/modified .md files after debounce."""
    detected: list[set[str]] = []

    async def on_changes(paths: set[str]):
        detected.append(paths)

    watcher = FileWatcher(tmp_path, debounce_seconds=0.5, on_changes=on_changes)
    await watcher.start()

    try:
        # Give watcher time to start
        await asyncio.sleep(0.3)

        # Create a file
        (tmp_path / "test.md").write_text("hello")

        # Wait for debounce to flush
        await asyncio.sleep(1.5)

        assert len(detected) >= 1
        all_paths = set()
        for batch in detected:
            all_paths.update(batch)
        assert "test.md" in all_paths
    finally:
        await watcher.stop()


@pytest.mark.asyncio
async def test_watcher_ignores_non_md(tmp_path: Path):
    """Watcher should ignore non-.md files."""
    detected: list[set[str]] = []

    async def on_changes(paths: set[str]):
        detected.append(paths)

    watcher = FileWatcher(tmp_path, debounce_seconds=0.5, on_changes=on_changes)
    await watcher.start()

    try:
        await asyncio.sleep(0.3)
        (tmp_path / "notes.txt").write_text("not markdown")
        await asyncio.sleep(1.5)
        assert len(detected) == 0
    finally:
        await watcher.stop()


@pytest.mark.asyncio
async def test_watcher_debounces_rapid_changes(tmp_path: Path):
    """Multiple rapid changes should be batched into one callback."""
    detected: list[set[str]] = []

    async def on_changes(paths: set[str]):
        detected.append(paths)

    watcher = FileWatcher(tmp_path, debounce_seconds=1.0, on_changes=on_changes)
    await watcher.start()

    try:
        await asyncio.sleep(0.3)
        # Rapid writes
        for i in range(5):
            (tmp_path / "test.md").write_text(f"version {i}")
            await asyncio.sleep(0.1)

        # Wait for debounce
        await asyncio.sleep(2.0)

        # Should be batched into few callbacks (ideally 1)
        all_paths = set()
        for batch in detected:
            all_paths.update(batch)
        assert "test.md" in all_paths
    finally:
        await watcher.stop()
```

Run: `cd packages/driver-phone && uv run pytest tests/test_watcher.py -v`

Commit: `git commit -m "feat(driver-phone): add FileWatcher with debounced real-time sync"`

---

## Task 7: Create CharacterSyncManager

**Goal:** Bidirectional `.doll` file transfer. When a new character is installed on either side, push it to the other. Characters are transferred as raw `.doll` zip bytes.

**Files:**
- Create: `packages/driver-phone/src/driver_phone/sync/character.py`

- [ ] **Step 1: Implement CharacterSyncManager**

Create `packages/driver-phone/src/driver_phone/sync/character.py`:

```python
"""Character sync: bidirectional .doll file transfer."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import TYPE_CHECKING

import structlog

from driver_phone.protocol import MessageType
from driver_phone.sync.constants import MAX_BATCH_SIZE

if TYPE_CHECKING:
    from driver_phone.session import PhoneSession

logger = structlog.get_logger()

# Max size for a .doll file transfer (50 MB, same as batch limit)
MAX_DOLL_SIZE = MAX_BATCH_SIZE


class CharacterSyncManager:
    """Handles .doll character pack sync between phone and server.

    .doll files are zip archives containing manifest.json, personality.json,
    voice.json, scene.json, model.glb, animations/, thumbnail.png.
    They are transferred as raw bytes — not unpacked during transfer.
    """

    def __init__(self, characters_root: Path) -> None:
        """
        Args:
            characters_root: /data/dollos/characters/ — where .doll packs
                are extracted on the server side.
        """
        self._root = characters_root
        self._root.mkdir(parents=True, exist_ok=True)

    # ── Send a character to the phone ──────────────────────────────────

    async def send_character(self, session: PhoneSession, doll_path: Path) -> bool:
        """Send a .doll file to the phone.

        Args:
            doll_path: Path to the .doll zip file.

        Returns:
            True if sent successfully.
        """
        if not doll_path.is_file():
            logger.warning("doll file not found", path=str(doll_path))
            return False

        size = doll_path.stat().st_size
        if size > MAX_DOLL_SIZE:
            logger.warning("doll file too large", path=str(doll_path), size=size)
            return False

        content = await asyncio.get_running_loop().run_in_executor(
            None, doll_path.read_bytes
        )

        await session.send(
            MessageType.CHARACTER_SYNC,
            {
                "filename": doll_path.name,
                "content": content,
                "size": len(content),
            },
        )
        logger.info("character sent", filename=doll_path.name, size=len(content))
        return True

    # ── Receive a character from the phone ─────────────────────────────

    async def handle_character_sync(self, payload: dict) -> str | None:
        """Handle an incoming character_sync message.

        Saves the .doll file to a staging area for the CharacterManager
        to import. Returns the path to the saved .doll file, or None on failure.
        """
        filename = payload.get("filename", "")
        content = payload.get("content", b"")

        if not filename or not filename.endswith(".doll"):
            logger.warning("invalid character sync filename", filename=filename)
            return None

        if isinstance(content, str):
            content = content.encode("utf-8")

        if len(content) > MAX_DOLL_SIZE:
            logger.warning("character file too large", size=len(content))
            return None

        # Save to staging directory
        staging_dir = self._root / ".staging"
        staging_dir.mkdir(parents=True, exist_ok=True)

        # Sanitize filename
        safe_name = Path(filename).name  # strip any directory components
        dest = staging_dir / safe_name

        await asyncio.get_running_loop().run_in_executor(
            None, dest.write_bytes, content
        )
        logger.info("character received and staged", filename=safe_name, size=len(content))
        return str(dest)

    # ── Character switch notification ──────────────────────────────────

    @staticmethod
    async def send_character_switch(
        session: PhoneSession, character_id: str
    ) -> None:
        """Notify the phone that the active character has changed."""
        await session.send(
            MessageType.CHARACTER_SWITCH,
            {"character_id": character_id},
        )
        logger.info("character switch notification sent", character_id=character_id)

    @staticmethod
    async def handle_character_switch(payload: dict) -> str | None:
        """Handle a character_switch message from the phone.

        Returns the character_id to switch to, or None if invalid.
        """
        character_id = payload.get("character_id")
        if not character_id or not isinstance(character_id, str):
            logger.warning("invalid character_switch payload")
            return None
        logger.info("character switch requested by phone", character_id=character_id)
        return character_id
```

Commit: `git commit -m "feat(driver-phone): add CharacterSyncManager for bidirectional .doll transfer"`

---

## Task 8: Add Character Sync Handlers to driver-phone

**Goal:** Wire `character_sync` and `character_switch` message handling into the WebSocket server. On `character_switch`, update the MemorySyncManager's active character and notify NATS.

**Files:**
- Modify: `packages/driver-phone/src/driver_phone/__init__.py`

- [ ] **Step 1: Initialize CharacterSyncManager**

In `PhoneWebSocketServer.__init__`, add:

```python
from driver_phone.sync.character import CharacterSyncManager

self._character_sync = CharacterSyncManager(
    Path(config.data_dir) / "characters"
)
```

- [ ] **Step 2: Add character message handlers**

In `_handle_message`, add before the "unknown message type" warning:

```python
if msg_type == MessageType.CHARACTER_SYNC and device_id:
    asyncio.create_task(self._handle_character_sync_msg(device_id, payload))
    return device_id

if msg_type == MessageType.CHARACTER_SWITCH and device_id:
    asyncio.create_task(self._handle_character_switch_msg(device_id, payload))
    return device_id
```

Add the handler methods:

```python
async def _handle_character_sync_msg(self, device_id: str, payload: dict) -> None:
    """Handle incoming character_sync: stage the .doll file and notify via NATS."""
    doll_path = await self._character_sync.handle_character_sync(payload)
    if doll_path:
        await self._publish_event(
            device_id, "character_install",
            {"doll_path": doll_path, "filename": payload.get("filename", "")},
        )

async def _handle_character_switch_msg(self, device_id: str, payload: dict) -> None:
    """Handle character_switch: update sync manager and notify via NATS."""
    from driver_phone.sync.character import CharacterSyncManager

    character_id = await CharacterSyncManager.handle_character_switch(payload)
    if character_id:
        # Update the memory sync manager to use the new character's directory
        mgr = self._sync_managers.get(device_id)
        if mgr:
            mgr.switch_character(character_id)

        # Update session
        session = self._sessions.get(device_id)
        if session:
            session.character_id = character_id

        # Restart watcher for new directory
        old_watcher = self._watchers.pop(device_id, None)
        if old_watcher:
            asyncio.create_task(old_watcher.stop())
        if mgr and session:
            async def _on_changes(paths, dev_id=device_id):
                s = self._sessions.get(dev_id)
                m = self._sync_managers.get(dev_id)
                if s and s.is_alive and m:
                    for p in paths:
                        await m.push_file(s, p)

            from driver_phone.sync.watcher import FileWatcher
            watcher = FileWatcher(mgr.memory_dir, on_changes=_on_changes)
            self._watchers[device_id] = watcher
            asyncio.create_task(watcher.start())

            # Trigger full sync for new character
            asyncio.create_task(mgr.full_sync(session))

        await self._publish_event(
            device_id, "character_switch",
            {"character_id": character_id},
        )
```

Commit: `git commit -m "feat(driver-phone): wire character sync and switch handlers into WebSocket server"`

---

## Task 9: Add Conflict Resolution Details

**Goal:** Ensure conflict resolution is robust: `.conflict.md` files are created for near-simultaneous edits, and old conflict files are auto-cleaned. Wire cleanup into a periodic task.

**Files:**
- Modify: `packages/driver-phone/src/driver_phone/sync/memory.py`
- Modify: `packages/driver-phone/src/driver_phone/__init__.py`

- [ ] **Step 1: Track conflict files in MemorySyncManager**

In `MemorySyncManager.handle_sync_request`, after computing the diff, store the conflict paths so that incoming `memory_file` messages for those paths are handled correctly:

```python
# Store pending conflicts so handle_memory_file knows which files are conflicts
self._pending_conflicts: set[str] = set(conflicts)
```

Update `handle_memory_file` to auto-detect conflict mode:

```python
async def handle_memory_file(self, payload: dict, *, is_conflict: bool = False) -> None:
    path = payload.get("path", "")
    # Auto-detect if this path was marked as a conflict during sync
    if path in getattr(self, '_pending_conflicts', set()):
        is_conflict = True
        self._pending_conflicts.discard(path)
    # ... rest of the method
```

- [ ] **Step 2: Add periodic conflict cleanup**

In `PhoneWebSocketServer.__init__`, add:

```python
self._cleanup_task: asyncio.Task | None = None
```

In `start()`, start the cleanup loop:

```python
self._cleanup_task = asyncio.create_task(self._conflict_cleanup_loop())
```

In `stop()`, cancel it:

```python
if self._cleanup_task:
    self._cleanup_task.cancel()
```

Add the cleanup loop:

```python
async def _conflict_cleanup_loop(self) -> None:
    """Periodically clean up expired .conflict.md files."""
    from driver_phone.sync.engine import SyncEngine
    from driver_phone.sync.constants import CONFLICT_CLEANUP_DAYS

    while True:
        try:
            await asyncio.sleep(3600)  # Run every hour
            if self._memory_root.is_dir():
                for char_dir in self._memory_root.iterdir():
                    if char_dir.is_dir():
                        deleted = await asyncio.get_running_loop().run_in_executor(
                            None,
                            SyncEngine.cleanup_conflicts,
                            char_dir,
                        )
                        if deleted:
                            logger.info("cleaned up conflicts", character=char_dir.name, count=len(deleted))
        except asyncio.CancelledError:
            break
        except Exception:
            logger.exception("conflict cleanup error")
```

Commit: `git commit -m "feat(driver-phone): add conflict tracking and periodic .conflict.md cleanup"`

---

## Task 10: Add File Size Limits and Validation

**Goal:** Enforce the 1 MB per-file and 50 MB per-batch limits at every entry point. Log warnings for skipped files.

**Files:**
- Modify: `packages/driver-phone/src/driver_phone/sync/memory.py` (already has limits, verify)
- Modify: `packages/driver-phone/src/driver_phone/sync/manifest.py` (already skips oversized, verify)

- [ ] **Step 1: Verify and add batch size tracking to handle_sync_request**

The limits are already implemented in the code from Tasks 2 and 4. This step is a review pass to ensure consistency:

1. `FileManifest.scan()` — skips files > `MAX_FILE_SIZE` (1 MB). Verified in Task 2.
2. `MemorySyncManager.handle_sync_request()` — tracks `batch_size` and stops at `MAX_BATCH_SIZE` (50 MB). Verified in Task 4.
3. `MemorySyncManager.handle_sync_response()` — same batch tracking. Verified in Task 4.
4. `MemorySyncManager.handle_memory_file()` — rejects content > `MAX_FILE_SIZE`. Verified in Task 4.
5. `MemorySyncManager.push_file()` — checks `MAX_FILE_SIZE`. Verified in Task 4.
6. `CharacterSyncManager` — checks `MAX_DOLL_SIZE` (50 MB). Verified in Task 7.

- [ ] **Step 2: Add batch tracking for incoming files during full sync**

Add to `MemorySyncManager`:

```python
def __init__(self, memory_root: Path, character_id: str) -> None:
    # ... existing init ...
    self._received_batch_size = 0

async def handle_memory_file(self, payload: dict, *, is_conflict: bool = False) -> None:
    # ... existing validation ...
    self._received_batch_size += len(content)
    if self._received_batch_size > MAX_BATCH_SIZE:
        logger.warning("incoming batch size limit reached", total=self._received_batch_size)
        return
    # ... rest of write logic ...

async def full_sync(self, session: PhoneSession) -> None:
    self._received_batch_size = 0  # Reset counter for new sync
    # ... existing logic ...
```

Commit: `git commit -m "feat(driver-phone): add incoming batch size limit tracking"`

---

## Task 11: Tests (Sync Engine, Conflict Resolution, Protocol Handshake)

**Goal:** Comprehensive test coverage for the sync system. All tests should run without a live WebSocket or NATS connection.

**Files:**
- Tests already created in Tasks 1-6. This task adds integration-level tests.
- Create: `packages/driver-phone/tests/test_sync_integration.py`

- [ ] **Step 1: Write integration tests**

Create `packages/driver-phone/tests/test_sync_integration.py`:

```python
"""Integration tests for the complete sync flow."""

import asyncio
import hashlib
import time
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from driver_phone.protocol import MessageType
from driver_phone.sync.constants import CONFLICT_SUFFIX
from driver_phone.sync.engine import SyncAction, SyncEngine
from driver_phone.sync.manifest import FileManifest
from driver_phone.sync.memory import MemorySyncManager


@pytest.fixture
def server_memory(tmp_path: Path) -> Path:
    """Server-side memory directory."""
    root = tmp_path / "server" / "memory"
    root.mkdir(parents=True)
    return root


@pytest.fixture
def phone_memory(tmp_path: Path) -> Path:
    """Simulated phone-side memory directory."""
    root = tmp_path / "phone" / "memory"
    root.mkdir(parents=True)
    return root


class TestFullSyncScenario:
    """Test complete sync flows between two directories."""

    @pytest.mark.asyncio
    async def test_first_connect_phone_has_memories_server_empty(
        self, server_memory, phone_memory
    ):
        """Phone connects for the first time, server has no memories."""
        char = "gura"
        # Phone has some memories
        phone_dir = phone_memory / char
        phone_dir.mkdir()
        (phone_dir / "MEMORY.md").write_text("# Gura's Memory\nMaster is kind.")
        (phone_dir / "people").mkdir()
        (phone_dir / "people" / "master.md").write_text("# Master\nLikes cats.")

        # Server is empty
        server_mgr = MemorySyncManager(server_memory, char)

        # Simulate: phone sends its manifest
        phone_manifest = FileManifest.scan(phone_dir)
        phone_wire = phone_manifest.to_wire()

        # Server computes diff
        mock_session = AsyncMock()
        await server_mgr.handle_sync_request(mock_session, {"manifest": phone_wire})

        # Server should request both files from phone
        response_call = mock_session.send.call_args_list[0]
        assert response_call[0][0] == MessageType.MEMORY_SYNC_RESPONSE
        need_files = response_call[0][1]["need_files"]
        assert set(need_files) == {"MEMORY.md", "people/master.md"}

    @pytest.mark.asyncio
    async def test_both_have_same_files(self, server_memory, phone_memory):
        """Both sides have identical files — nothing transferred."""
        char = "gura"
        content = "# Same content"
        for root in [server_memory / char, phone_memory / char]:
            root.mkdir(parents=True)
            (root / "MEMORY.md").write_text(content)

        server_mgr = MemorySyncManager(server_memory, char)
        phone_manifest = FileManifest.scan(phone_memory / char)

        mock_session = AsyncMock()
        await server_mgr.handle_sync_request(mock_session, {"manifest": phone_manifest.to_wire()})

        # Only sync_response sent, no files
        assert mock_session.send.call_count == 1
        response = mock_session.send.call_args[0][1]
        assert response["need_files"] == []

    @pytest.mark.asyncio
    async def test_conflict_creates_conflict_file(self, server_memory):
        """Near-simultaneous edits create .conflict.md."""
        char = "gura"
        server_dir = server_memory / char
        server_dir.mkdir()
        (server_dir / "MEMORY.md").write_text("server version")

        server_mgr = MemorySyncManager(server_memory, char)

        # Phone has different content with similar mtime
        now = time.time()
        phone_entry = {
            "path": "MEMORY.md",
            "sha256": hashlib.sha256(b"phone version").hexdigest(),
            "mtime": now - 2,  # 2 seconds ago (within 5s threshold)
            "size": len(b"phone version"),
        }

        import os
        os.utime(server_dir / "MEMORY.md", (now - 1, now - 1))

        mock_session = AsyncMock()
        await server_mgr.handle_sync_request(mock_session, {"manifest": [phone_entry]})

        response = mock_session.send.call_args_list[0][0][1]
        assert "MEMORY.md" in response["conflict_files"]

    @pytest.mark.asyncio
    async def test_receive_file_writes_to_disk(self, server_memory):
        """Receiving a memory_file should write it to the correct location."""
        char = "gura"
        mgr = MemorySyncManager(server_memory, char)

        await mgr.handle_memory_file({
            "path": "topics/music.md",
            "content": b"# Music\nJazz is great.",
            "mtime": time.time(),
        })

        written = (server_memory / char / "topics" / "music.md").read_text()
        assert written == "# Music\nJazz is great."


class TestConflictCleanup:
    def test_old_conflicts_removed(self, tmp_path: Path):
        """Conflict files older than 7 days should be auto-deleted."""
        import os

        old_conflict = tmp_path / "old.conflict.md"
        old_conflict.write_text("stale")
        old_time = time.time() - (8 * 86400)
        os.utime(old_conflict, (old_time, old_time))

        fresh_conflict = tmp_path / "fresh.conflict.md"
        fresh_conflict.write_text("recent")

        normal_file = tmp_path / "normal.md"
        normal_file.write_text("not a conflict")

        deleted = SyncEngine.cleanup_conflicts(tmp_path, max_age_days=7)

        assert "old.conflict.md" in deleted
        assert not old_conflict.exists()
        assert fresh_conflict.exists()
        assert normal_file.exists()


class TestEdgeCases:
    @pytest.mark.asyncio
    async def test_empty_manifest_from_phone(self, server_memory):
        """Phone with no memories should still get all server memories."""
        char = "gura"
        server_dir = server_memory / char
        server_dir.mkdir()
        (server_dir / "MEMORY.md").write_text("server memory")

        mgr = MemorySyncManager(server_memory, char)
        mock_session = AsyncMock()
        await mgr.handle_sync_request(mock_session, {"manifest": []})

        # Should send the file
        calls = mock_session.send.call_args_list
        file_calls = [c for c in calls if c[0][0] == MessageType.MEMORY_FILE]
        assert len(file_calls) == 1
        assert file_calls[0][0][1]["path"] == "MEMORY.md"

    @pytest.mark.asyncio
    async def test_oversized_file_rejected(self, server_memory):
        """Files over 1 MB should be rejected."""
        mgr = MemorySyncManager(server_memory, "gura")
        await mgr.handle_memory_file({
            "path": "huge.md",
            "content": b"x" * (1024 * 1024 + 1),
            "mtime": time.time(),
        })
        assert not (server_memory / "gura" / "huge.md").exists()

    @pytest.mark.asyncio
    async def test_path_traversal_blocked(self, server_memory):
        """Path traversal attempts should be blocked."""
        mgr = MemorySyncManager(server_memory, "gura")
        await mgr.handle_memory_file({
            "path": "../../../etc/shadow",
            "content": b"evil",
            "mtime": time.time(),
        })
        # No file should be written outside the memory dir
```

- [ ] **Step 2: Run full test suite**

```bash
cd packages/driver-phone && uv run pytest tests/ -v --tb=short
```

Commit: `git commit -m "test(driver-phone): add sync integration tests for full sync flow, conflicts, and edge cases"`

---

## Summary

| Task | What | Server Files | Tests |
|------|------|-------------|-------|
| 1 | Protocol v1 envelope + version negotiation | `protocol.py`, `sync/constants.py` | `test_protocol_v1.py` |
| 2 | FileManifest (scan + SHA-256) | `sync/manifest.py` | `test_manifest.py` |
| 3 | SyncEngine (diff + conflict resolution) | `sync/engine.py` | `test_engine.py` |
| 4 | MemorySyncManager (full + real-time sync) | `sync/memory.py` | `test_memory_sync.py` |
| 5 | Wire handlers into WebSocket server | `__init__.py`, `session.py` | existing tests |
| 6 | FileWatcher (watchfiles + debounce) | `sync/watcher.py`, `pyproject.toml` | `test_watcher.py` |
| 7 | CharacterSyncManager (.doll transfer) | `sync/character.py` | — |
| 8 | Wire character handlers | `__init__.py` | — |
| 9 | Conflict tracking + periodic cleanup | `sync/memory.py`, `__init__.py` | covered by Task 3 |
| 10 | File size limit enforcement | review pass | covered by Tasks 2, 4 |
| 11 | Integration tests | — | `test_sync_integration.py` |

### Not in Scope (handled elsewhere)

- **Android-side WebSocket client** — the phone app's connection logic is in DollOS-Android, not driver-phone. The protocol defined here is what both sides implement.
- **sqlite-vec index rebuild after sync** — triggered by memsearch (Section 1 of the design spec), not by the sync protocol itself. After writing files, the sync manager can publish a NATS event `event.memory.files_changed` for memsearch to pick up.
- **Authentication (TOTP / pre-shared key)** — mentioned in the design spec but not detailed here. Should be a separate task wired into the WebSocket handshake before HELLO.
- **Network setup (Tailscale / DDNS)** — explicitly out of scope per design spec.
