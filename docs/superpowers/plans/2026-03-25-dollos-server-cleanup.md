# DollOS-Server Cleanup & Docker Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Milvus, etcd, Attu, MinIO/RustFS from Docker stack and Python dependencies. Final state: only NATS as external dependency in docker-compose.

**Architecture:** Remove infra modules (milvus.py, rustfs.py), Docker compose services, and Python package dependencies. Update all imports and config references throughout codebase. Write a one-time data migration script.

**Tech Stack:** Docker Compose, Python (uv package manager), sqlite-vec

**Prerequisite:** This plan MUST be run AFTER the memsearch implementation plan is complete, since the new storage (sqlite-vec + local filesystem) must be in place before removing the old backends.

---

## Overview of Files to Modify

### Files to DELETE

| File | Purpose |
|------|---------|
| `smolgura/infra/milvus.py` | MilvusClient wrapper |
| `smolgura/infra/rustfs.py` | RustFSClient (MinIO S3 wrapper) |
| `tests/test_infra/test_milvus.py` | Milvus unit tests |
| `tests/test_infra/test_rustfs.py` | RustFS unit tests |
| `tests/test_integration/test_memory_milvus.py` | Milvus integration tests |

### Files to EDIT

| File | Change |
|------|--------|
| `docker-compose.yml` | Remove `milvus` service, `rustfs` service, related volumes |
| `pyproject.toml` | Remove `pymilvus`, `minio`, `RestrictedPython`; remove protobuf override |
| `smolgura/config.py` | Remove `MilvusConfig`, `RustFSConfig` classes and references in `SystemConfig` |
| `smolgura/guraos.py` | Remove Milvus/RustFS client init, imports, `MilvusVectorStore` usage |
| `smolgura/services/memory.py` | Remove `MilvusVectorStore` class and `MilvusClient` TYPE_CHECKING import |
| `smolgura/audio/speaker.py` | Remove `COLLECTION_SPEAKERS` import, `MilvusClient` TYPE_CHECKING import (will use sqlite-vec after memsearch) |
| `smolgura/vision/memory.py` | Remove `MilvusClient` and `RustFSClient` TYPE_CHECKING imports (will use memsearch/local FS after memsearch) |
| `smolgura/gura/core.py` | Remove `RustFSClient` TYPE_CHECKING import |
| `tests/test_integration/conftest.py` | Remove Milvus fixture and imports |
| `tests/test_services/test_memory_update.py` | Remove `MilvusVectorStore` import |
| `packages/driver-discord/src/driver_discord/bot.py` | Remove `MilvusClient` TYPE_CHECKING import |

---

## Tasks

### Task 1: Remove docker/compose.milvus.yml (if it exists)

The design spec lists `docker/compose.milvus.yml` for removal. The file does not currently exist at that path (Milvus was already inlined into `docker-compose.yml`), so this task is a no-op verification.

- [ ] Verify `~/Projects/DollOS-Server/docker/compose.milvus.yml` does not exist
- [ ] If it exists, delete it
- [ ] If `docker-compose.yml` has an `include` referencing it, remove that include line

```bash
# Verify
ls ~/Projects/DollOS-Server/docker/compose.milvus.yml 2>/dev/null && echo "EXISTS - delete it" || echo "Already gone"
```

**Commit:** `chore: remove docker/compose.milvus.yml if present`

---

### Task 2: Remove Milvus and RustFS services from docker-compose.yml

**File:** `~/Projects/DollOS-Server/docker-compose.yml`

- [ ] Delete the entire `milvus` service block (lines 39–63: container `smolgura-milvus`, image `milvusdb/milvus:v2.4.24`, all environment/ports/volumes/healthcheck/networks)
- [ ] Delete the entire `rustfs` service block (lines 65–94: container `smolgura-rustfs`, image `rustfs/rustfs:latest`, all environment/ports/volumes/healthcheck/networks)
- [ ] Remove these volume definitions from the `volumes:` section at the bottom:
  - `milvus-data` (name: `smolgura-milvus-data`)
  - `rustfs-data` (name: `smolgura-rustfs-data`)
  - `rustfs-logs` (name: `smolgura-rustfs-logs`)
- [ ] Keep: `nats` service, `nats-data` volume, `smolgura` network, kmod services, `include: docker-compose.infer.yml`
- [ ] Update the file header comment to reflect new usage:
  ```
  # Barebone: `docker compose up -d`         → NATS only
  # With local inference: `docker compose --profile local-infer up -d`
  # With kmods: `docker compose --profile kmod up -d`
  ```

**Expected final `docker-compose.yml` structure:**
```yaml
include:
  - docker-compose.infer.yml

services:
  nats:
    # ... (unchanged)

  fun-asr:
    # ... (unchanged, profile: kmod)

  fish-speech:
    # ... (unchanged, profile: kmod)

networks:
  smolgura:
    name: smolgura
    driver: bridge

volumes:
  nats-data:
    name: smolgura-nats-data
```

**Commit:** `chore: remove Milvus and RustFS services from docker-compose`

---

### Task 3: Remove pymilvus, minio, and RestrictedPython from pyproject.toml

**File:** `~/Projects/DollOS-Server/pyproject.toml`

- [ ] Remove from `[project] dependencies`:
  - `"pymilvus>=2.6.4",`
  - `"minio>=7.2.0",`
  - `"RestrictedPython>=8.0",`
- [ ] Remove or update the protobuf override in `[tool.uv] override-dependencies`:
  - The override exists solely to resolve pymilvus vs descript-audiotools conflict
  - Delete the entire `override-dependencies` list (lines 147–152):
    ```toml
    override-dependencies = [
        # descript-audiotools and descript-audio-codec pin protobuf<3.20
        # but actually work fine with protobuf 5.x. Override to resolve
        # conflict with pymilvus which requires protobuf>=5.27.
        "protobuf>=5.27.2",
    ]
    ```
  - NOTE: If descript-audiotools still needs protobuf override for other reasons after pymilvus removal, keep it but update the comment. Test with `uv lock` first.
- [ ] Remove `"milvus: tests requiring Milvus server",` from `[tool.pytest.ini_options] markers`
- [ ] Remove the `smolgura/shell/errors.py` per-file-ignore in `[tool.ruff.lint.per-file-ignores]` (shell/ is being removed in the tool execution plan, but if it's already gone, this line will cause no harm — remove it here for hygiene):
  ```toml
  "smolgura/shell/errors.py" = ["N818"]  # Remove this line
  ```

**Commit:** `chore: remove pymilvus, minio, RestrictedPython from dependencies`

---

### Task 4: Delete infra/milvus.py

**File:** `~/Projects/DollOS-Server/smolgura/infra/milvus.py`

- [ ] Delete the entire file

```bash
rm ~/Projects/DollOS-Server/smolgura/infra/milvus.py
```

**Commit:** `chore: remove infra/milvus.py`

---

### Task 5: Delete infra/rustfs.py

**File:** `~/Projects/DollOS-Server/smolgura/infra/rustfs.py`

- [ ] Delete the entire file

```bash
rm ~/Projects/DollOS-Server/smolgura/infra/rustfs.py
```

**Commit:** `chore: remove infra/rustfs.py`

---

### Task 6: Remove MilvusConfig and RustFSConfig from config.py

**File:** `~/Projects/DollOS-Server/smolgura/config.py`

- [ ] Delete the `MilvusConfig` class (around line 20–23):
  ```python
  class MilvusConfig(BaseModel):
      """Milvus vector database configuration."""
      url: str = Field("http://localhost:19530", description="Milvus server URL")
  ```
- [ ] Delete the `RustFSConfig` class (around lines 39–45):
  ```python
  class RustFSConfig(BaseModel):
      """RustFS (S3-compatible) object storage configuration."""
      endpoint: str = Field("localhost:9000", description="RustFS endpoint")
      access_key: str = Field("rustfsadmin", description="Access key")
      secret_key: SecretStr = Field(default=SecretStr("rustfsadmin"), description="Secret key")
      secure: bool = Field(False, description="Use TLS")
  ```
- [ ] Remove these fields from `SystemConfig` (around lines 180–184):
  ```python
  milvus: MilvusConfig = Field(default_factory=MilvusConfig)
  rustfs: RustFSConfig = Field(default_factory=RustFSConfig, description="RustFS (MinIO) object storage")
  ```
- [ ] If `SecretStr` import is only used by `RustFSConfig`, remove it from imports

**Commit:** `chore: remove MilvusConfig and RustFSConfig from config`

---

### Task 7: Remove Milvus/RustFS initialization from guraos.py

**File:** `~/Projects/DollOS-Server/smolgura/guraos.py`

- [ ] Remove import at line 43:
  ```python
  from smolgura.services.memory import FakeVectorStore, MemoryService, MilvusVectorStore
  ```
  Replace with (after memsearch is in place):
  ```python
  from smolgura.services.memory import MemoryService
  ```
  (FakeVectorStore may still be needed for tests — check usage. If only used in test mode, keep it.)
- [ ] In `_init_real_mode()` (around line 188), remove:
  ```python
  from smolgura.infra.milvus import MilvusClient
  ```
  ```python
  from smolgura.infra.rustfs import RustFSClient
  ```
- [ ] Remove client instantiation (around lines 204, 209):
  ```python
  self._milvus_client = MilvusClient(config.system.milvus, config.system.embedding_dimension)
  ```
  ```python
  self._rustfs_client: RustFSClient | None = RustFSClient(config.system.rustfs)
  ```
- [ ] Remove `self._milvus_client` and `self._rustfs_client` attributes entirely
- [ ] Search for any `await self._milvus_client.connect()` and `await self._rustfs_client.connect()` in `_boot()` and remove them
- [ ] Search for any `await self._milvus_client.close()` and `await self._rustfs_client.close()` in shutdown and remove them
- [ ] Update any `MilvusVectorStore(...)` construction to use the new memsearch backend
- [ ] Grep for any remaining references to `milvus` or `rustfs` in guraos.py and remove them

**Commit:** `refactor: remove Milvus/RustFS initialization from GuraOS`

---

### Task 8: Clean up services/memory.py

**File:** `~/Projects/DollOS-Server/smolgura/services/memory.py`

- [ ] Remove the `MilvusClient` TYPE_CHECKING import (line 14):
  ```python
  from smolgura.infra.milvus import MilvusClient
  ```
- [ ] Remove or refactor the `MilvusVectorStore` class (this class wraps MilvusClient for the MemoryService). After memsearch is in place, this class is dead code.
- [ ] Keep `FakeVectorStore` if it's used by tests (check `test_memory_update.py`)
- [ ] Update `MemoryService` to no longer accept `MilvusVectorStore` — it should use the new memsearch backend

**Commit:** `refactor: remove MilvusVectorStore from memory service`

---

### Task 9: Clean up audio/speaker.py

**File:** `~/Projects/DollOS-Server/smolgura/audio/speaker.py`

- [ ] Remove line 12:
  ```python
  from smolgura.infra.milvus import COLLECTION_SPEAKERS
  ```
- [ ] Remove TYPE_CHECKING import (lines 16–17):
  ```python
  from smolgura.infra.milvus import MilvusClient
  ```
- [ ] The speaker service should already be using sqlite-vec after memsearch plan. Verify that `COLLECTION_SPEAKERS` constant is no longer referenced. If the speaker service still needs a collection name constant, define it locally.

**Commit:** `refactor: remove Milvus imports from speaker service`

---

### Task 10: Clean up vision/memory.py

**File:** `~/Projects/DollOS-Server/smolgura/vision/memory.py`

- [ ] Remove TYPE_CHECKING imports (lines 20–21):
  ```python
  from smolgura.infra.milvus import MilvusClient
  from smolgura.infra.rustfs import RustFSClient
  ```
- [ ] Update module docstring (lines 1–7) to reflect new storage:
  ```python
  """ImageMemory - Store and recall image memories with bidirectional search.

  Storage layers:
  - SQLite (ImageMemoryEntry): metadata (description, source, timestamps)
  - sqlite-vec (memory.db): description embedding vectors
  - Local filesystem (/data/dollos/images/): image bytes
  """
  ```
- [ ] After memsearch plan is complete, `ImageMemory` should already be using local FS + sqlite-vec. Verify no remaining Milvus/RustFS method calls.

**Commit:** `refactor: remove Milvus/RustFS imports from vision memory`

---

### Task 11: Clean up gura/core.py

**File:** `~/Projects/DollOS-Server/smolgura/gura/core.py`

- [ ] Remove TYPE_CHECKING import (line 37):
  ```python
  from smolgura.infra.rustfs import RustFSClient
  ```
- [ ] Remove any `self._rustfs` or `rustfs_client` attribute/parameter references in GuraCore
- [ ] Verify GuraCore no longer passes RustFSClient to ImageMemory or other components

**Commit:** `refactor: remove RustFS import from GuraCore`

---

### Task 12: Clean up driver-discord bot.py

**File:** `~/Projects/DollOS-Server/packages/driver-discord/src/driver_discord/bot.py`

- [ ] Remove TYPE_CHECKING import (line 37):
  ```python
  from smolgura.infra.milvus import MilvusClient
  ```
- [ ] Remove any `milvus_client` parameter or attribute usage

**Commit:** `refactor: remove MilvusClient import from discord driver`

---

### Task 13: Delete Milvus and RustFS test files

- [ ] Delete `~/Projects/DollOS-Server/tests/test_infra/test_milvus.py`
- [ ] Delete `~/Projects/DollOS-Server/tests/test_infra/test_rustfs.py`
- [ ] Delete `~/Projects/DollOS-Server/tests/test_integration/test_memory_milvus.py`

```bash
rm ~/Projects/DollOS-Server/tests/test_infra/test_milvus.py
rm ~/Projects/DollOS-Server/tests/test_infra/test_rustfs.py
rm ~/Projects/DollOS-Server/tests/test_integration/test_memory_milvus.py
```

**Commit:** `test: remove Milvus and RustFS test files`

---

### Task 14: Clean up test fixtures

**File:** `~/Projects/DollOS-Server/tests/test_integration/conftest.py`

- [ ] Remove Milvus-related imports (around lines 169, 175):
  ```python
  from smolgura.infra.milvus import MilvusClient
  import smolgura.infra.milvus as milvus_mod
  ```
- [ ] Remove the `from pymilvus import MilvusClient as PyMilvusClient` import (line 74)
- [ ] Remove any Milvus fixture functions (e.g., `milvus_client` fixture)
- [ ] Remove any RustFS fixture functions if present

**File:** `~/Projects/DollOS-Server/tests/test_services/test_memory_update.py`

- [ ] Remove import (line 13):
  ```python
  from smolgura.services.memory import FakeVectorStore, MemoryService, MilvusVectorStore
  ```
  Replace with:
  ```python
  from smolgura.services.memory import FakeVectorStore, MemoryService
  ```
- [ ] Remove any test cases that specifically test `MilvusVectorStore`

**Commit:** `test: remove Milvus/RustFS fixtures and test references`

---

### Task 15: Regenerate uv.lock

- [ ] Run `uv lock` to regenerate the lockfile without pymilvus, minio, RestrictedPython:
  ```bash
  cd ~/Projects/DollOS-Server && uv lock
  ```
- [ ] Verify the lock resolved correctly (no errors)
- [ ] Check that `protobuf` is no longer pulled in (unless another dep needs it):
  ```bash
  grep protobuf ~/Projects/DollOS-Server/uv.lock
  ```
- [ ] Run `uv sync` to update the local virtualenv:
  ```bash
  cd ~/Projects/DollOS-Server && uv sync
  ```

**Commit:** `chore: regenerate uv.lock after dependency removal`

---

### Task 16: Verify docker compose up works with only NATS

- [ ] Bring down any existing containers:
  ```bash
  cd ~/Projects/DollOS-Server && docker compose down
  ```
- [ ] Start with barebone (NATS only):
  ```bash
  docker compose up -d
  ```
- [ ] Verify only `smolgura-nats` container is running:
  ```bash
  docker compose ps
  ```
- [ ] Check NATS healthcheck passes:
  ```bash
  docker compose ps nats | grep healthy
  ```
- [ ] Verify old volumes can be cleaned up (informational, do NOT auto-delete):
  ```bash
  echo "Old volumes to manually clean up:"
  docker volume ls | grep -E "milvus|rustfs"
  ```

**Commit:** No commit needed — this is a verification step.

---

### Task 17: Write data migration script

**File:** `~/Projects/DollOS-Server/scripts/migrate_milvus_to_markdown.py`

This is a one-time migration script for existing installations. New installations start from scratch.

- [ ] Create `scripts/migrate_milvus_to_markdown.py` with the following logic:

**Prerequisites:** Old docker-compose must still be running with Milvus and RustFS accessible.

**Script structure:**
```python
"""One-time migration: Milvus + RustFS → Markdown + local filesystem + sqlite-vec.

Run BEFORE removing Milvus/RustFS containers.
Usage: python scripts/migrate_milvus_to_markdown.py --data-dir /data/dollos
"""
```

**Migration steps the script must perform:**

1. **Connect to old Milvus** (pymilvus, hardcoded or CLI-arg URL `http://localhost:19530`)
2. **Connect to old RustFS** (minio SDK, endpoint `localhost:9000`)
3. **Export memory entries from Milvus:**
   - Query all collections/partitions
   - Cross-reference with Tortoise ORM `MemoryEntry` table for text content and metadata
   - Categorize into `people/`, `topics/`, `decisions/` based on partition/metadata
   - Write as Markdown files to `/data/dollos/memory/<character-id>/`
4. **Generate MEMORY.md** from `## User Profile` state entries
5. **Export daily memories** from episodic partition, grouped by date → `YYYY-MM-DD.md`
6. **Export speaker profiles** from Milvus `speaker_profiles` collection → sqlite-vec `speakers.db`
7. **Download images from RustFS** to `/data/dollos/images/<character-id>/`
   - Migrate `ImageMemoryEntry` metadata (descriptions, embeddings) to sqlite-vec `image_memory` table
8. **Rebuild sqlite-vec index** from all exported Markdown files
9. **Print summary** of migrated items

**Error handling:**
- Skip items that fail, log warnings, continue
- Idempotent — safe to re-run (checks for existing files)
- `--dry-run` flag to preview without writing

**Dependencies:** The script needs `pymilvus` and `minio` — since we're removing them from main deps, add a note that the script should be run BEFORE Task 3 (dependency removal), or install them temporarily:
```bash
uv run --with pymilvus --with minio python scripts/migrate_milvus_to_markdown.py
```

**Commit:** `feat: add Milvus-to-Markdown migration script`

---

### Task 18: Final sweep — grep for any remaining references

- [ ] Run a project-wide search for any lingering references:
  ```bash
  cd ~/Projects/DollOS-Server
  grep -rn "milvus\|Milvus\|MILVUS" --include="*.py" --include="*.toml" --include="*.yml" --include="*.yaml" --include="*.md" .
  grep -rn "rustfs\|RustFS\|RUSTFS\|MinIO\|minio" --include="*.py" --include="*.toml" --include="*.yml" --include="*.yaml" .
  grep -rn "pymilvus" --include="*.py" --include="*.toml" .
  ```
- [ ] Fix any remaining references found
- [ ] Run the test suite to verify nothing is broken:
  ```bash
  cd ~/Projects/DollOS-Server && uv run pytest tests/ -x --ignore=tests/test_integration
  ```

**Commit:** `chore: final cleanup of Milvus/RustFS references`

---

## Execution Order

```
Task 17 (migration script) — run FIRST while old infra is still available
  ↓
Task 1  (compose.milvus.yml)
Task 2  (docker-compose.yml cleanup)
  ↓
Task 3  (pyproject.toml)
Task 4  (delete infra/milvus.py)
Task 5  (delete infra/rustfs.py)
  ↓
Task 6  (config.py)
Task 7  (guraos.py)
Task 8  (services/memory.py)
Task 9  (audio/speaker.py)
Task 10 (vision/memory.py)
Task 11 (gura/core.py)
Task 12 (driver-discord/bot.py)
  ↓
Task 13 (delete test files)
Task 14 (clean test fixtures)
  ↓
Task 15 (uv lock)
Task 16 (verify docker compose)
Task 18 (final sweep)
```

Tasks within the same level can be done in parallel. Tasks at a lower level depend on the level above.
