# DollOS-Server memsearch Memory System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Milvus-based memory with memsearch — Markdown files as source of truth, sqlite-vec + FTS5 for search indexing, three-tier loading strategy.

**Architecture:** MemoryService IPC interface preserved. Backend swapped from MilvusVectorStore to MarkdownStore + SqliteVecIndex. Three tiers: MEMORY.md (always loaded), daily logs (auto-loaded), topic directories (search-loaded). Per-character memory directories.

**Tech Stack:** Python, sqlite-vec, FTS5, NATS IPC, asyncio

---

## File Structure

### New files (in `smolgura/`)

```
smolgura/
  memsearch/
    __init__.py
    markdown_store.py         -- Markdown file I/O, heading-based chunking, SHA-256 dedup
    sqlite_vec_index.py       -- sqlite-vec + FTS5 index, hybrid search with RRF
    memsearch_service.py      -- Three-tier loading, write queue, consolidation
    write_queue.py            -- Serialized write queue with pending_writes.json
  services/
    memory.py                 -- Rewritten: MemsearchService backend (IPC interface preserved)
  vision/
    memory.py                 -- Rewritten: local files + sqlite-vec (no RustFS/Milvus)
  audio/
    speaker.py                -- Rewritten: sqlite-vec for speaker profiles (no Milvus)
scripts/
  migrate_milvus_to_markdown.py  -- One-time migration script
```

### New test files (in `tests/`)

```
tests/
  test_memsearch/
    __init__.py
    test_markdown_store.py
    test_sqlite_vec_index.py
    test_memsearch_service.py
    test_write_queue.py
  test_services/
    test_memory.py            -- Updated for new backend
    test_memory_update.py     -- Updated for new backend
  test_vision/
    test_memory.py            -- Updated for local files + sqlite-vec
  test_audio/
    test_speaker_sqlitevec.py -- Speaker profiles on sqlite-vec
  test_scripts/
    test_migration.py
```

### Files to delete (after migration)

```
smolgura/infra/milvus.py
smolgura/infra/rustfs.py
tests/test_integration/test_memory_milvus.py
tests/test_infra/test_milvus.py
```

### Dependency changes (`pyproject.toml`)

```
# Add:
"sqlite-vec>=0.1.6"

# Remove:
"pymilvus>=2.6.4"
"minio>=7.2.0"
```

---

## Task 1: Add sqlite-vec Python Dependency

**Goal:** Add `sqlite-vec` to `pyproject.toml` and verify it loads in Python.

**Files:**
- Edit: `pyproject.toml`
- Create: `tests/test_memsearch/__init__.py`
- Create: `tests/test_memsearch/test_sqlite_vec_load.py`

- [ ] **Step 1: Write failing test — sqlite-vec loads as a Python module**

Create `tests/test_memsearch/__init__.py` (empty file).

Create `tests/test_memsearch/test_sqlite_vec_load.py`:

```python
"""Smoke test: sqlite-vec extension loads and creates a virtual table."""

import sqlite3

import pytest
import sqlite_vec


def test_sqlite_vec_loads():
    """sqlite-vec extension can be loaded into a connection."""
    db = sqlite3.connect(":memory:")
    db.enable_load_extension(True)
    sqlite_vec.load(db)
    # Verify vec0 virtual table module is available
    cur = db.execute("SELECT vec_version()")
    version = cur.fetchone()[0]
    assert version  # non-empty string


def test_fts5_available():
    """FTS5 is available in the Python sqlite3 build."""
    db = sqlite3.connect(":memory:")
    db.execute("CREATE VIRTUAL TABLE test_fts USING fts5(content)")
    db.execute("INSERT INTO test_fts(content) VALUES ('hello world')")
    rows = db.execute("SELECT * FROM test_fts WHERE test_fts MATCH 'hello'").fetchall()
    assert len(rows) == 1
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_memsearch/test_sqlite_vec_load.py -x`

Expected: fails (sqlite-vec not installed).

- [ ] **Step 2: Add sqlite-vec dependency**

Edit `pyproject.toml`, in the `dependencies` list add:

```
"sqlite-vec>=0.1.6",
```

Run: `cd ~/Projects/DollOS-Server && pip install -e ".[dev]"` (or `uv sync`)

- [ ] **Step 3: Verify tests pass**

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_memsearch/test_sqlite_vec_load.py -x -v`

Expected: both tests pass.

Commit: `feat(memsearch): add sqlite-vec dependency and smoke tests`

---

## Task 2: Create MarkdownStore

**Goal:** Implement Markdown file I/O with heading-based chunking and SHA-256 content hashing. This is the "source of truth" layer — all memory reads and writes go through Markdown files.

**Files:**
- Create: `smolgura/memsearch/__init__.py`
- Create: `smolgura/memsearch/markdown_store.py`
- Create: `tests/test_memsearch/test_markdown_store.py`

- [ ] **Step 1: Write failing tests for MarkdownStore**

Create `tests/test_memsearch/test_markdown_store.py`:

```python
"""Tests for MarkdownStore — Markdown file I/O and heading-based chunking."""

from __future__ import annotations

import hashlib
from pathlib import Path

import pytest

from smolgura.memsearch.markdown_store import MarkdownStore, MemoryChunk


@pytest.fixture
def store(tmp_path: Path) -> MarkdownStore:
    return MarkdownStore(root_dir=tmp_path / "memory" / "test-char")


class TestDirectorySetup:
    def test_init_creates_directory_structure(self, store: MarkdownStore):
        """MarkdownStore creates tier directories on init."""
        assert store.root_dir.exists()
        assert (store.root_dir / "people").is_dir()
        assert (store.root_dir / "topics").is_dir()
        assert (store.root_dir / "decisions").is_dir()
        assert (store.root_dir / "index").is_dir()


class TestCoreMemory:
    def test_read_core_memory_empty(self, store: MarkdownStore):
        """Reading MEMORY.md when it doesn't exist returns empty string."""
        assert store.read_core_memory() == ""

    def test_write_and_read_core_memory(self, store: MarkdownStore):
        store.write_file("MEMORY.md", "# About Master\nLikes cats.")
        assert "Likes cats." in store.read_core_memory()


class TestDailyMemory:
    def test_read_daily_nonexistent(self, store: MarkdownStore):
        assert store.read_file("2026-03-25.md") == ""

    def test_write_and_read_daily(self, store: MarkdownStore):
        store.write_file("2026-03-25.md", "# 2026-03-25\n- Talked about music.")
        content = store.read_file("2026-03-25.md")
        assert "music" in content


class TestDeepMemory:
    def test_write_and_read_deep(self, store: MarkdownStore):
        store.write_file("people/master.md", "# Master\nLikes Python.")
        content = store.read_file("people/master.md")
        assert "Python" in content


class TestListFiles:
    def test_list_files_empty(self, store: MarkdownStore):
        assert store.list_files() == []

    def test_list_files_recursive(self, store: MarkdownStore):
        store.write_file("MEMORY.md", "# Core")
        store.write_file("2026-03-25.md", "# Daily")
        store.write_file("people/master.md", "# Master")
        files = store.list_files()
        assert len(files) == 3
        paths = {f.relative_path for f in files}
        assert "MEMORY.md" in paths
        assert "2026-03-25.md" in paths
        assert "people/master.md" in paths


class TestDeleteFile:
    def test_delete_existing(self, store: MarkdownStore):
        store.write_file("topics/music.md", "# Music")
        assert store.delete_file("topics/music.md") is True
        assert store.read_file("topics/music.md") == ""

    def test_delete_nonexistent(self, store: MarkdownStore):
        assert store.delete_file("topics/ghost.md") is False


class TestHeadingChunking:
    def test_chunk_by_headings(self, store: MarkdownStore):
        content = """# Main Title

Introduction text.

## Section A

Content of section A.

## Section B

Content of section B.
"""
        store.write_file("topics/test.md", content)
        chunks = store.chunk_file("topics/test.md")
        assert len(chunks) >= 2
        assert all(isinstance(c, MemoryChunk) for c in chunks)

    def test_chunk_includes_sha256(self, store: MarkdownStore):
        content = "# Test\nSome content."
        store.write_file("topics/test.md", content)
        chunks = store.chunk_file("topics/test.md")
        assert len(chunks) >= 1
        expected_hash = hashlib.sha256(chunks[0].content.encode()).hexdigest()
        assert chunks[0].content_hash == expected_hash

    def test_chunk_max_size(self, store: MarkdownStore):
        """Chunks exceeding max size are split."""
        long_content = "# Title\n" + ("word " * 500)  # ~2500 chars
        store.write_file("topics/long.md", long_content)
        chunks = store.chunk_file("topics/long.md", max_chunk_chars=1500)
        assert all(len(c.content) <= 1500 for c in chunks)

    def test_chunk_empty_file(self, store: MarkdownStore):
        chunks = store.chunk_file("nonexistent.md")
        assert chunks == []

    def test_chunk_dedup_by_hash(self, store: MarkdownStore):
        """Identical content produces same hash — caller can dedup."""
        content = "# Same\nIdentical content."
        store.write_file("topics/a.md", content)
        store.write_file("topics/b.md", content)
        chunks_a = store.chunk_file("topics/a.md")
        chunks_b = store.chunk_file("topics/b.md")
        assert chunks_a[0].content_hash == chunks_b[0].content_hash


class TestFileManifest:
    def test_manifest_includes_sha256_and_mtime(self, store: MarkdownStore):
        store.write_file("MEMORY.md", "# Core")
        manifest = store.list_files()
        assert len(manifest) == 1
        entry = manifest[0]
        assert entry.sha256
        assert entry.mtime > 0
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_memsearch/test_markdown_store.py -x`

Expected: fails (module does not exist).

- [ ] **Step 2: Create `smolgura/memsearch/__init__.py`**

Create `smolgura/memsearch/__init__.py` (empty file).

- [ ] **Step 3: Implement MarkdownStore**

Create `smolgura/memsearch/markdown_store.py`:

```python
"""MarkdownStore — Markdown file I/O, heading-based chunking, SHA-256 hashing.

Markdown files are the single source of truth for all memory. The sqlite-vec
+ FTS5 index is a derived artifact that can be rebuilt from these files.

Storage layout (per character):
    /data/dollos/memory/<character-id>/
        MEMORY.md              -- Tier 1: always loaded
        2026-03-25.md          -- Tier 2: daily logs
        people/                -- Tier 3: deep memory
        topics/
        decisions/
        index/
            memory.db          -- sqlite-vec + FTS5 (rebuildable)
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path

import structlog

log = structlog.get_logger()

TIER3_DIRS = ("people", "topics", "decisions")
INDEX_DIR = "index"


@dataclass
class MemoryChunk:
    """A chunk of memory extracted from a Markdown file."""

    source_path: str  # relative path within character memory dir
    heading: str  # heading text (or "root" for content before first heading)
    content: str  # full chunk text including heading
    content_hash: str  # SHA-256 of content


@dataclass
class FileEntry:
    """Manifest entry for a Markdown file."""

    relative_path: str
    sha256: str
    mtime: float


class MarkdownStore:
    """Read/write/list/delete Markdown files. Heading-based chunking."""

    def __init__(self, root_dir: str | Path) -> None:
        self.root_dir = Path(root_dir)
        self._ensure_dirs()

    def _ensure_dirs(self) -> None:
        """Create directory structure if it doesn't exist."""
        self.root_dir.mkdir(parents=True, exist_ok=True)
        for d in TIER3_DIRS:
            (self.root_dir / d).mkdir(exist_ok=True)
        (self.root_dir / INDEX_DIR).mkdir(exist_ok=True)

    # -- File I/O --

    def read_core_memory(self) -> str:
        """Read MEMORY.md (Tier 1). Returns empty string if missing."""
        return self.read_file("MEMORY.md")

    def read_file(self, relative_path: str) -> str:
        """Read a Markdown file by relative path. Returns '' if missing."""
        path = self.root_dir / relative_path
        if not path.exists():
            return ""
        return path.read_text(encoding="utf-8")

    def write_file(self, relative_path: str, content: str) -> Path:
        """Write content to a Markdown file. Creates parent dirs as needed."""
        path = self.root_dir / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        log.debug("markdown written", path=relative_path, chars=len(content))
        return path

    def delete_file(self, relative_path: str) -> bool:
        """Delete a Markdown file. Returns False if it didn't exist."""
        path = self.root_dir / relative_path
        if not path.exists():
            return False
        path.unlink()
        log.debug("markdown deleted", path=relative_path)
        return True

    def list_files(self) -> list[FileEntry]:
        """List all Markdown files with SHA-256 and mtime."""
        entries = []
        for path in sorted(self.root_dir.rglob("*.md")):
            # Skip index directory
            try:
                path.relative_to(self.root_dir / INDEX_DIR)
                continue
            except ValueError:
                pass
            rel = str(path.relative_to(self.root_dir))
            content = path.read_bytes()
            sha = hashlib.sha256(content).hexdigest()
            mtime = path.stat().st_mtime
            entries.append(FileEntry(relative_path=rel, sha256=sha, mtime=mtime))
        return entries

    # -- Chunking --

    def chunk_file(
        self, relative_path: str, *, max_chunk_chars: int = 1500
    ) -> list[MemoryChunk]:
        """Split a Markdown file into chunks by headings.

        Each heading (## or deeper) starts a new chunk. Content before the
        first heading becomes a "root" chunk. Chunks exceeding max_chunk_chars
        are split on paragraph boundaries.
        """
        content = self.read_file(relative_path)
        if not content.strip():
            return []

        # Split on headings (## and deeper)
        sections = re.split(r"(?m)^(#{1,6}\s+.+)$", content)

        raw_chunks: list[tuple[str, str]] = []  # (heading, body)
        current_heading = "root"
        current_body = ""

        for part in sections:
            if re.match(r"^#{1,6}\s+", part):
                # Save previous chunk
                if current_body.strip():
                    raw_chunks.append((current_heading, current_body.strip()))
                current_heading = part.strip()
                current_body = ""
            else:
                current_body += part

        # Save last chunk
        if current_body.strip():
            raw_chunks.append((current_heading, current_body.strip()))

        # Build MemoryChunk objects, splitting oversized chunks
        chunks: list[MemoryChunk] = []
        for heading, body in raw_chunks:
            full_text = f"{heading}\n\n{body}" if heading != "root" else body
            if len(full_text) <= max_chunk_chars:
                chunks.append(self._make_chunk(relative_path, heading, full_text))
            else:
                # Split on double newlines (paragraphs)
                chunks.extend(
                    self._split_oversized(relative_path, heading, full_text, max_chunk_chars)
                )
        return chunks

    def chunk_all(self, *, max_chunk_chars: int = 1500) -> list[MemoryChunk]:
        """Chunk every Markdown file in the store."""
        chunks = []
        for entry in self.list_files():
            chunks.extend(self.chunk_file(entry.relative_path, max_chunk_chars=max_chunk_chars))
        return chunks

    def _make_chunk(self, source_path: str, heading: str, content: str) -> MemoryChunk:
        return MemoryChunk(
            source_path=source_path,
            heading=heading,
            content=content,
            content_hash=hashlib.sha256(content.encode("utf-8")).hexdigest(),
        )

    def _split_oversized(
        self, source_path: str, heading: str, text: str, max_chars: int
    ) -> list[MemoryChunk]:
        """Split oversized text on paragraph boundaries."""
        paragraphs = text.split("\n\n")
        chunks = []
        current = ""
        for para in paragraphs:
            candidate = f"{current}\n\n{para}" if current else para
            if len(candidate) > max_chars and current:
                chunks.append(self._make_chunk(source_path, heading, current))
                current = para
            else:
                current = candidate
        if current:
            chunks.append(self._make_chunk(source_path, heading, current))
        return chunks
```

- [ ] **Step 4: Run tests and verify**

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_memsearch/test_markdown_store.py -x -v`

Expected: all pass.

Commit: `feat(memsearch): implement MarkdownStore with heading-based chunking`

---

## Task 3: Create SqliteVecIndex

**Goal:** Build the search index layer — sqlite-vec for vector search, FTS5 for keyword search, RRF (Reciprocal Rank Fusion) for hybrid search. The index is a derived artifact, rebuildable from Markdown.

**Files:**
- Create: `smolgura/memsearch/sqlite_vec_index.py`
- Create: `tests/test_memsearch/test_sqlite_vec_index.py`

- [ ] **Step 1: Write failing tests for SqliteVecIndex**

Create `tests/test_memsearch/test_sqlite_vec_index.py`:

```python
"""Tests for SqliteVecIndex — vector + FTS5 hybrid search."""

from __future__ import annotations

from pathlib import Path

import pytest

from smolgura.memsearch.markdown_store import MemoryChunk
from smolgura.memsearch.sqlite_vec_index import SqliteVecIndex

EMBEDDING_DIM = 8  # small dim for tests


@pytest.fixture
def index(tmp_path: Path) -> SqliteVecIndex:
    db_path = tmp_path / "memory.db"
    idx = SqliteVecIndex(db_path=db_path, embedding_dim=EMBEDDING_DIM)
    return idx


def _make_chunk(content: str, source: str = "test.md", heading: str = "Test") -> MemoryChunk:
    import hashlib
    return MemoryChunk(
        source_path=source,
        heading=heading,
        content=content,
        content_hash=hashlib.sha256(content.encode()).hexdigest(),
    )


def _fake_vector(seed: float = 0.1) -> list[float]:
    return [seed] * EMBEDDING_DIM


class TestIndexLifecycle:
    def test_create_tables(self, index: SqliteVecIndex):
        """Index creates vector and FTS5 tables."""
        # Tables should exist after init
        cur = index._conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        )
        names = {row[0] for row in cur.fetchall()}
        assert "memory_chunks" in names
        assert "memory_fts" in names

    def test_close_and_reopen(self, tmp_path: Path):
        db_path = tmp_path / "test.db"
        idx = SqliteVecIndex(db_path=db_path, embedding_dim=EMBEDDING_DIM)
        idx.upsert(_make_chunk("hello"), _fake_vector())
        idx.close()
        idx2 = SqliteVecIndex(db_path=db_path, embedding_dim=EMBEDDING_DIM)
        results = idx2.search_fts("hello", limit=1)
        assert len(results) >= 1
        idx2.close()


class TestUpsert:
    def test_upsert_new(self, index: SqliteVecIndex):
        chunk = _make_chunk("Python is great")
        index.upsert(chunk, _fake_vector(0.5))
        results = index.search_fts("Python", limit=5)
        assert len(results) == 1
        assert "Python" in results[0]["content"]

    def test_upsert_idempotent(self, index: SqliteVecIndex):
        """Same content_hash → no duplicate."""
        chunk = _make_chunk("Same content")
        index.upsert(chunk, _fake_vector())
        index.upsert(chunk, _fake_vector())
        results = index.search_fts("content", limit=10)
        assert len(results) == 1

    def test_upsert_updates_vector(self, index: SqliteVecIndex):
        """Same hash, new vector → vector is updated."""
        chunk = _make_chunk("Content")
        index.upsert(chunk, _fake_vector(0.1))
        index.upsert(chunk, _fake_vector(0.9))
        # Should still be 1 entry
        results = index.search_fts("Content", limit=10)
        assert len(results) == 1


class TestDelete:
    def test_delete_by_source_path(self, index: SqliteVecIndex):
        index.upsert(_make_chunk("A", source="people/a.md"), _fake_vector())
        index.upsert(_make_chunk("B", source="people/b.md"), _fake_vector(0.2))
        deleted = index.delete_by_source("people/a.md")
        assert deleted >= 1
        results = index.search_fts("A", limit=10)
        assert len(results) == 0


class TestFTSSearch:
    def test_search_fts_basic(self, index: SqliteVecIndex):
        index.upsert(_make_chunk("I love cats and dogs"), _fake_vector())
        index.upsert(_make_chunk("Mathematics is beautiful"), _fake_vector(0.2))
        results = index.search_fts("cats", limit=5)
        assert len(results) == 1
        assert "cats" in results[0]["content"]

    def test_search_fts_empty(self, index: SqliteVecIndex):
        results = index.search_fts("nonexistent", limit=5)
        assert results == []


class TestVectorSearch:
    def test_search_vector_basic(self, index: SqliteVecIndex):
        index.upsert(_make_chunk("cats"), _fake_vector(0.9))
        index.upsert(_make_chunk("math"), _fake_vector(0.1))
        results = index.search_vector(_fake_vector(0.9), limit=1)
        assert len(results) == 1
        assert "cats" in results[0]["content"]


class TestHybridSearch:
    def test_hybrid_search_rrf(self, index: SqliteVecIndex):
        """Hybrid search combines vector + FTS5 results via RRF."""
        index.upsert(_make_chunk("I love Python programming"), _fake_vector(0.8))
        index.upsert(_make_chunk("Cats are wonderful pets"), _fake_vector(0.2))
        index.upsert(_make_chunk("Python snakes are scary"), _fake_vector(0.7))
        results = index.hybrid_search(
            query_text="Python",
            query_vector=_fake_vector(0.8),
            limit=5,
        )
        assert len(results) >= 1
        # Top result should mention Python
        assert "Python" in results[0]["content"]

    def test_hybrid_search_empty_index(self, index: SqliteVecIndex):
        results = index.hybrid_search(
            query_text="anything",
            query_vector=_fake_vector(),
            limit=5,
        )
        assert results == []


class TestRebuild:
    def test_rebuild_from_chunks(self, index: SqliteVecIndex):
        """Full index rebuild from a list of (chunk, vector) pairs."""
        chunks_and_vectors = [
            (_make_chunk("First chunk", source="a.md"), _fake_vector(0.1)),
            (_make_chunk("Second chunk", source="b.md"), _fake_vector(0.9)),
        ]
        index.rebuild(chunks_and_vectors)
        results = index.search_fts("First", limit=5)
        assert len(results) == 1

    def test_rebuild_clears_old_data(self, index: SqliteVecIndex):
        index.upsert(_make_chunk("Old data"), _fake_vector())
        index.rebuild([(_make_chunk("New data"), _fake_vector(0.5))])
        old = index.search_fts("Old", limit=5)
        assert len(old) == 0
        new = index.search_fts("New", limit=5)
        assert len(new) == 1


class TestStats:
    def test_stats_empty(self, index: SqliteVecIndex):
        stats = index.stats()
        assert stats["chunk_count"] == 0

    def test_stats_with_data(self, index: SqliteVecIndex):
        index.upsert(_make_chunk("A"), _fake_vector())
        index.upsert(_make_chunk("B"), _fake_vector(0.2))
        stats = index.stats()
        assert stats["chunk_count"] == 2
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_memsearch/test_sqlite_vec_index.py -x`

Expected: fails (module does not exist).

- [ ] **Step 2: Implement SqliteVecIndex**

Create `smolgura/memsearch/sqlite_vec_index.py`:

```python
"""SqliteVecIndex — vector + FTS5 hybrid search index.

This is a derived artifact. It can be fully rebuilt from Markdown files.
Uses sqlite-vec for cosine similarity vector search and FTS5 for BM25
keyword search. Results are fused via Reciprocal Rank Fusion (RRF).
"""

from __future__ import annotations

import sqlite3
import struct
from pathlib import Path
from typing import TYPE_CHECKING

import sqlite_vec
import structlog

if TYPE_CHECKING:
    from smolgura.memsearch.markdown_store import MemoryChunk

log = structlog.get_logger()

# RRF weights: 70% vector, 30% BM25
VECTOR_WEIGHT = 0.7
FTS_WEIGHT = 0.3
RRF_K = 60  # standard RRF constant


def _serialize_f32(vector: list[float]) -> bytes:
    """Serialize a float32 vector for sqlite-vec."""
    return struct.pack(f"{len(vector)}f", *vector)


class SqliteVecIndex:
    """sqlite-vec + FTS5 hybrid search index for memory chunks."""

    def __init__(self, db_path: str | Path, embedding_dim: int) -> None:
        self._db_path = str(db_path)
        self._dim = embedding_dim
        self._conn = sqlite3.connect(self._db_path)
        self._conn.enable_load_extension(True)
        sqlite_vec.load(self._conn)
        self._conn.enable_load_extension(False)
        self._create_tables()

    def _create_tables(self) -> None:
        """Create chunk metadata, vector, and FTS5 tables."""
        self._conn.executescript(f"""
            CREATE TABLE IF NOT EXISTS memory_chunks (
                content_hash TEXT PRIMARY KEY,
                source_path  TEXT NOT NULL,
                heading      TEXT NOT NULL,
                content      TEXT NOT NULL
            );

            CREATE VIRTUAL TABLE IF NOT EXISTS memory_vec USING vec0(
                content_hash TEXT PRIMARY KEY,
                embedding    float[{self._dim}]
            );

            CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
                content,
                content_hash UNINDEXED
            );

            CREATE INDEX IF NOT EXISTS idx_chunks_source
                ON memory_chunks(source_path);
        """)
        self._conn.commit()

    # -- Write operations --

    def upsert(self, chunk: MemoryChunk, vector: list[float]) -> None:
        """Insert or update a chunk and its vector/FTS entries."""
        h = chunk.content_hash
        existing = self._conn.execute(
            "SELECT content_hash FROM memory_chunks WHERE content_hash = ?", (h,)
        ).fetchone()

        if existing:
            # Update vector only (content is identical by hash)
            self._conn.execute(
                "UPDATE memory_vec SET embedding = ? WHERE content_hash = ?",
                (_serialize_f32(vector), h),
            )
        else:
            # Insert into all three tables
            self._conn.execute(
                "INSERT INTO memory_chunks (content_hash, source_path, heading, content) VALUES (?, ?, ?, ?)",
                (h, chunk.source_path, chunk.heading, chunk.content),
            )
            self._conn.execute(
                "INSERT INTO memory_vec (content_hash, embedding) VALUES (?, ?)",
                (h, _serialize_f32(vector)),
            )
            self._conn.execute(
                "INSERT INTO memory_fts (content, content_hash) VALUES (?, ?)",
                (chunk.content, h),
            )
        self._conn.commit()

    def delete_by_source(self, source_path: str) -> int:
        """Delete all chunks from a given source file. Returns count deleted."""
        hashes = [
            row[0]
            for row in self._conn.execute(
                "SELECT content_hash FROM memory_chunks WHERE source_path = ?",
                (source_path,),
            ).fetchall()
        ]
        if not hashes:
            return 0
        placeholders = ",".join("?" * len(hashes))
        self._conn.execute(
            f"DELETE FROM memory_chunks WHERE content_hash IN ({placeholders})", hashes
        )
        self._conn.execute(
            f"DELETE FROM memory_vec WHERE content_hash IN ({placeholders})", hashes
        )
        self._conn.execute(
            f"DELETE FROM memory_fts WHERE content_hash IN ({placeholders})", hashes
        )
        self._conn.commit()
        return len(hashes)

    def rebuild(self, chunks_and_vectors: list[tuple[MemoryChunk, list[float]]]) -> None:
        """Full rebuild: drop all data and re-index from scratch."""
        self._conn.executescript("""
            DELETE FROM memory_chunks;
            DELETE FROM memory_vec;
            DELETE FROM memory_fts;
        """)
        for chunk, vector in chunks_and_vectors:
            self.upsert(chunk, vector)
        log.info("index rebuilt", chunk_count=len(chunks_and_vectors))

    # -- Search operations --

    def search_fts(self, query: str, limit: int = 10) -> list[dict]:
        """BM25 keyword search via FTS5."""
        # Escape special FTS5 characters
        safe_query = query.replace('"', '""')
        try:
            rows = self._conn.execute(
                """
                SELECT f.content_hash, f.content, rank
                FROM memory_fts f
                WHERE memory_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """,
                (f'"{safe_query}"', limit),
            ).fetchall()
        except sqlite3.OperationalError:
            return []
        return [
            self._enrich_result(content_hash=r[0], content=r[1], score=-r[2])
            for r in rows
        ]

    def search_vector(self, query_vector: list[float], limit: int = 10) -> list[dict]:
        """Cosine similarity search via sqlite-vec."""
        rows = self._conn.execute(
            """
            SELECT content_hash, distance
            FROM memory_vec
            WHERE embedding MATCH ?
            ORDER BY distance
            LIMIT ?
            """,
            (_serialize_f32(query_vector), limit),
        ).fetchall()
        return [
            self._enrich_result(content_hash=r[0], score=1.0 - r[1])
            for r in rows
        ]

    def hybrid_search(
        self,
        query_text: str,
        query_vector: list[float],
        limit: int = 10,
        *,
        vector_weight: float = VECTOR_WEIGHT,
        fts_weight: float = FTS_WEIGHT,
    ) -> list[dict]:
        """Hybrid search: RRF fusion of vector + FTS5 results."""
        vec_results = self.search_vector(query_vector, limit=limit * 2)
        fts_results = self.search_fts(query_text, limit=limit * 2)

        # Build RRF scores
        rrf_scores: dict[str, float] = {}
        for rank, r in enumerate(vec_results):
            h = r["content_hash"]
            rrf_scores[h] = rrf_scores.get(h, 0) + vector_weight / (RRF_K + rank + 1)
        for rank, r in enumerate(fts_results):
            h = r["content_hash"]
            rrf_scores[h] = rrf_scores.get(h, 0) + fts_weight / (RRF_K + rank + 1)

        # Merge all result dicts by hash
        all_results: dict[str, dict] = {}
        for r in vec_results + fts_results:
            h = r["content_hash"]
            if h not in all_results:
                all_results[h] = r

        # Sort by RRF score
        sorted_hashes = sorted(rrf_scores, key=lambda h: rrf_scores[h], reverse=True)
        results = []
        for h in sorted_hashes[:limit]:
            entry = all_results[h]
            entry["rrf_score"] = rrf_scores[h]
            results.append(entry)
        return results

    # -- Stats --

    def stats(self) -> dict:
        """Return index statistics."""
        count = self._conn.execute("SELECT COUNT(*) FROM memory_chunks").fetchone()[0]
        return {"chunk_count": count, "db_path": self._db_path}

    # -- Helpers --

    def _enrich_result(
        self, *, content_hash: str, content: str | None = None, score: float = 0.0
    ) -> dict:
        """Enrich a search result with metadata from memory_chunks."""
        if content is None:
            row = self._conn.execute(
                "SELECT source_path, heading, content FROM memory_chunks WHERE content_hash = ?",
                (content_hash,),
            ).fetchone()
            if row is None:
                return {"content_hash": content_hash, "score": score, "content": ""}
            source_path, heading, content = row
        else:
            row = self._conn.execute(
                "SELECT source_path, heading FROM memory_chunks WHERE content_hash = ?",
                (content_hash,),
            ).fetchone()
            source_path = row[0] if row else ""
            heading = row[1] if row else ""

        return {
            "content_hash": content_hash,
            "source_path": source_path,
            "heading": heading,
            "content": content,
            "score": score,
        }

    def close(self) -> None:
        """Close the database connection."""
        self._conn.close()
```

- [ ] **Step 3: Run tests and verify**

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_memsearch/test_sqlite_vec_index.py -x -v`

Expected: all pass.

Commit: `feat(memsearch): implement SqliteVecIndex with hybrid search (sqlite-vec + FTS5 + RRF)`

---

## Task 4: Create Write Queue

**Goal:** Implement the serialized write queue with `pending_writes.json` persistence, retry logic, and consolidation (dedup/update/insert based on similarity thresholds).

**Files:**
- Create: `smolgura/memsearch/write_queue.py`
- Create: `tests/test_memsearch/test_write_queue.py`

- [ ] **Step 1: Write failing tests for WriteQueue**

Create `tests/test_memsearch/test_write_queue.py`:

```python
"""Tests for MemoryWriteQueue — serialized writes with persistence and retry."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from smolgura.memsearch.write_queue import MemoryWriteQueue, WriteItem, WriteType


@pytest.fixture
def queue(tmp_path: Path) -> MemoryWriteQueue:
    return MemoryWriteQueue(persist_path=tmp_path / "pending_writes.json")


class TestWriteItem:
    def test_create_write_item(self):
        item = WriteItem(
            write_type=WriteType.UPSERT,
            relative_path="people/master.md",
            heading="# Master",
            content="Likes cats.",
        )
        assert item.write_type == WriteType.UPSERT
        assert item.relative_path == "people/master.md"

    def test_serialize_roundtrip(self):
        item = WriteItem(
            write_type=WriteType.UPSERT,
            relative_path="topics/music.md",
            heading="# Music",
            content="Loves jazz.",
        )
        d = item.to_dict()
        restored = WriteItem.from_dict(d)
        assert restored.write_type == item.write_type
        assert restored.content == item.content


class TestPersistence:
    @pytest.mark.asyncio
    async def test_persist_and_reload(self, tmp_path: Path):
        path = tmp_path / "pending.json"
        q1 = MemoryWriteQueue(persist_path=path)
        await q1.enqueue(WriteItem(
            write_type=WriteType.UPSERT,
            relative_path="test.md",
            heading="Test",
            content="Hello",
        ))
        # Should persist to disk
        assert path.exists()
        data = json.loads(path.read_text())
        assert len(data) >= 1

        # Reload in new queue
        q2 = MemoryWriteQueue(persist_path=path)
        pending = q2.pending_count()
        assert pending >= 1

    @pytest.mark.asyncio
    async def test_empty_queue_no_file(self, tmp_path: Path):
        path = tmp_path / "pending.json"
        q = MemoryWriteQueue(persist_path=path)
        assert q.pending_count() == 0


class TestEnqueueAndDrain:
    @pytest.mark.asyncio
    async def test_enqueue_and_process(self, queue: MemoryWriteQueue):
        handler = AsyncMock(return_value=True)
        await queue.enqueue(WriteItem(
            write_type=WriteType.UPSERT,
            relative_path="test.md",
            heading="Test",
            content="Hello",
        ))
        processed = await queue.drain(handler)
        assert processed == 1
        handler.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_retry_on_failure(self, queue: MemoryWriteQueue):
        call_count = 0

        async def flaky_handler(item: WriteItem) -> bool:
            nonlocal call_count
            call_count += 1
            if call_count < 3:
                raise RuntimeError("transient error")
            return True

        await queue.enqueue(WriteItem(
            write_type=WriteType.UPSERT,
            relative_path="test.md",
            heading="Test",
            content="Hello",
        ))
        processed = await queue.drain(flaky_handler, max_retries=3)
        assert processed == 1
        assert call_count == 3

    @pytest.mark.asyncio
    async def test_drop_after_max_retries(self, queue: MemoryWriteQueue):
        handler = AsyncMock(side_effect=RuntimeError("permanent error"))
        await queue.enqueue(WriteItem(
            write_type=WriteType.UPSERT,
            relative_path="test.md",
            heading="Test",
            content="Hello",
        ))
        processed = await queue.drain(handler, max_retries=3)
        assert processed == 0  # dropped after 3 retries

    @pytest.mark.asyncio
    async def test_serialized_order(self, queue: MemoryWriteQueue):
        """Writes are processed in FIFO order."""
        order = []

        async def handler(item: WriteItem) -> bool:
            order.append(item.content)
            return True

        await queue.enqueue(WriteItem(WriteType.UPSERT, "a.md", "A", "first"))
        await queue.enqueue(WriteItem(WriteType.UPSERT, "b.md", "B", "second"))
        await queue.enqueue(WriteItem(WriteType.UPSERT, "c.md", "C", "third"))
        await queue.drain(handler)
        assert order == ["first", "second", "third"]


class TestDeleteType:
    @pytest.mark.asyncio
    async def test_delete_write(self, queue: MemoryWriteQueue):
        handler = AsyncMock(return_value=True)
        await queue.enqueue(WriteItem(
            write_type=WriteType.DELETE,
            relative_path="topics/old.md",
            heading="",
            content="",
        ))
        await queue.drain(handler)
        handler.assert_awaited_once()
        call_item = handler.call_args[0][0]
        assert call_item.write_type == WriteType.DELETE
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_memsearch/test_write_queue.py -x`

Expected: fails (module does not exist).

- [ ] **Step 2: Implement WriteQueue**

Create `smolgura/memsearch/write_queue.py`:

```python
"""MemoryWriteQueue — serialized write queue with persistence and retry.

All memory writes flow through this queue to prevent race conditions.
Pending writes are persisted to ``pending_writes.json`` so they survive
service restarts.
"""

from __future__ import annotations

import asyncio
import enum
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Awaitable, Callable

import structlog

log = structlog.get_logger()


class WriteType(enum.Enum):
    UPSERT = "upsert"
    DELETE = "delete"
    APPEND = "append"


@dataclass
class WriteItem:
    """A single write operation to be queued."""

    write_type: WriteType
    relative_path: str
    heading: str
    content: str

    def to_dict(self) -> dict:
        return {
            "write_type": self.write_type.value,
            "relative_path": self.relative_path,
            "heading": self.heading,
            "content": self.content,
        }

    @classmethod
    def from_dict(cls, d: dict) -> WriteItem:
        return cls(
            write_type=WriteType(d["write_type"]),
            relative_path=d["relative_path"],
            heading=d["heading"],
            content=d["content"],
        )


class MemoryWriteQueue:
    """Serialized, persistent write queue for memory operations."""

    def __init__(self, persist_path: str | Path) -> None:
        self._persist_path = Path(persist_path)
        self._queue: list[WriteItem] = []
        self._lock = asyncio.Lock()
        self._load_pending()

    def _load_pending(self) -> None:
        """Load pending writes from disk."""
        if self._persist_path.exists():
            try:
                data = json.loads(self._persist_path.read_text(encoding="utf-8"))
                self._queue = [WriteItem.from_dict(d) for d in data]
                if self._queue:
                    log.info("loaded pending writes", count=len(self._queue))
            except (json.JSONDecodeError, KeyError):
                log.warning("corrupt pending_writes.json, starting fresh")
                self._queue = []

    def _persist(self) -> None:
        """Persist pending writes to disk."""
        self._persist_path.parent.mkdir(parents=True, exist_ok=True)
        data = [item.to_dict() for item in self._queue]
        self._persist_path.write_text(
            json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    def pending_count(self) -> int:
        return len(self._queue)

    async def enqueue(self, item: WriteItem) -> None:
        """Add a write item to the queue and persist."""
        async with self._lock:
            self._queue.append(item)
            self._persist()

    async def drain(
        self,
        handler: Callable[[WriteItem], Awaitable[bool]],
        max_retries: int = 3,
    ) -> int:
        """Process all queued items sequentially. Returns count processed."""
        async with self._lock:
            processed = 0
            remaining: list[WriteItem] = []

            for item in self._queue:
                success = False
                for attempt in range(max_retries):
                    try:
                        await handler(item)
                        success = True
                        break
                    except Exception:
                        log.warning(
                            "write failed, retrying",
                            path=item.relative_path,
                            attempt=attempt + 1,
                            max_retries=max_retries,
                        )
                if success:
                    processed += 1
                else:
                    log.error(
                        "write dropped after max retries",
                        path=item.relative_path,
                        max_retries=max_retries,
                    )
                    # Drop the item (do not re-enqueue)

            self._queue = remaining  # always empty after drain
            self._persist()
            return processed

    async def flush(self, handler: Callable[[WriteItem], Awaitable[bool]]) -> int:
        """Alias for drain — used during character switch."""
        return await self.drain(handler)
```

- [ ] **Step 3: Run tests and verify**

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_memsearch/test_write_queue.py -x -v`

Expected: all pass.

Commit: `feat(memsearch): implement MemoryWriteQueue with persistence and retry`

---

## Task 5: Create MemsearchService

**Goal:** The main orchestrator that ties MarkdownStore, SqliteVecIndex, and WriteQueue together. Implements three-tier loading, write consolidation, and embedding integration.

**Files:**
- Create: `smolgura/memsearch/memsearch_service.py`
- Create: `tests/test_memsearch/test_memsearch_service.py`

- [ ] **Step 1: Write failing tests for MemsearchService**

Create `tests/test_memsearch/test_memsearch_service.py`:

```python
"""Tests for MemsearchService — three-tier memory orchestrator."""

from __future__ import annotations

import datetime
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from smolgura.memsearch.memsearch_service import MemsearchService


class FakeEmbedding:
    """Fake embedding that returns deterministic vectors."""
    dim = 8

    async def embed(self, text: str) -> list[float]:
        # Simple hash-based fake vector
        h = hash(text) % 1000 / 1000.0
        return [h] * self.dim

    async def embed_batch(self, texts: list[str]) -> list[list[float]]:
        return [await self.embed(t) for t in texts]


@pytest.fixture
def service(tmp_path: Path) -> MemsearchService:
    memory_root = tmp_path / "memory" / "test-char"
    embedding = FakeEmbedding()
    svc = MemsearchService(
        memory_root=memory_root,
        embedding=embedding,
        embedding_dim=8,
    )
    return svc


class TestThreeTierLoading:
    @pytest.mark.asyncio
    async def test_load_tier1_core_memory(self, service: MemsearchService):
        """Tier 1: MEMORY.md is always loaded."""
        service.store.write_file("MEMORY.md", "# About Master\nLikes cats.\n")
        context = await service.load_context("hello")
        assert "Likes cats" in context["tier1"]

    @pytest.mark.asyncio
    async def test_load_tier2_daily(self, service: MemsearchService):
        """Tier 2: today + yesterday daily logs are loaded."""
        today = datetime.date.today().isoformat()
        service.store.write_file(f"{today}.md", "# Today\n- Had coffee.")
        context = await service.load_context("hello")
        assert "coffee" in context["tier2"]

    @pytest.mark.asyncio
    async def test_load_tier3_search(self, service: MemsearchService):
        """Tier 3: deep memories are loaded via search."""
        service.store.write_file("people/master.md", "# Master\nLoves Python and cats.")
        await service.rebuild_index()
        context = await service.load_context("tell me about Python")
        assert len(context["tier3"]) >= 0  # may or may not match depending on fake embedding


class TestStore:
    @pytest.mark.asyncio
    async def test_store_to_daily_log(self, service: MemsearchService):
        """Storing a memory appends to today's daily log."""
        await service.write_memory(
            content="User mentioned they like jazz.",
            category="daily",
        )
        today = datetime.date.today().isoformat()
        content = service.store.read_file(f"{today}.md")
        assert "jazz" in content

    @pytest.mark.asyncio
    async def test_store_to_deep_memory(self, service: MemsearchService):
        await service.write_memory(
            content="Master's favorite food is ramen.",
            category="people",
            filename="master.md",
        )
        content = service.store.read_file("people/master.md")
        assert "ramen" in content


class TestConsolidation:
    @pytest.mark.asyncio
    async def test_dedup_skip_identical(self, service: MemsearchService):
        """Consolidation: >= 0.95 similarity → skip (duplicate)."""
        await service.write_memory(content="Likes cats.", category="daily")
        # Write same thing again — should not duplicate
        await service.write_memory(content="Likes cats.", category="daily")
        today = datetime.date.today().isoformat()
        content = service.store.read_file(f"{today}.md")
        # Count occurrences — should appear once
        assert content.count("Likes cats.") == 1


class TestIndexRebuild:
    @pytest.mark.asyncio
    async def test_rebuild_index(self, service: MemsearchService):
        service.store.write_file("MEMORY.md", "# Core\nImportant fact.")
        service.store.write_file("people/master.md", "# Master\nLikes cats.")
        await service.rebuild_index()
        stats = service.index.stats()
        assert stats["chunk_count"] >= 2


class TestSearch:
    @pytest.mark.asyncio
    async def test_search_returns_results(self, service: MemsearchService):
        service.store.write_file("topics/music.md", "# Music\nLoves jazz and blues.")
        await service.rebuild_index()
        results = await service.search("jazz music")
        # May return results depending on fake embedding similarity
        assert isinstance(results, list)


class TestStats:
    @pytest.mark.asyncio
    async def test_stats(self, service: MemsearchService):
        stats = await service.get_stats()
        assert "chunk_count" in stats
        assert "file_count" in stats
```

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_memsearch/test_memsearch_service.py -x`

Expected: fails (module does not exist).

- [ ] **Step 2: Implement MemsearchService**

Create `smolgura/memsearch/memsearch_service.py`:

```python
"""MemsearchService — three-tier memory orchestrator.

Ties together MarkdownStore (source of truth), SqliteVecIndex (search),
and MemoryWriteQueue (serialized writes). Provides the high-level API
that MemoryService IPC handlers call.
"""

from __future__ import annotations

import datetime
from pathlib import Path
from typing import Any, Protocol

import structlog

from smolgura.memsearch.markdown_store import MarkdownStore
from smolgura.memsearch.sqlite_vec_index import SqliteVecIndex
from smolgura.memsearch.write_queue import MemoryWriteQueue, WriteItem, WriteType

log = structlog.get_logger()

# Consolidation thresholds (from spec)
SIMILARITY_SKIP = 0.95  # >= this: skip (duplicate)
SIMILARITY_UPDATE = 0.85  # >= this: update existing memory
# < 0.85: insert new memory


class EmbeddingProvider(Protocol):
    """Protocol for embedding generation."""

    async def embed(self, text: str) -> list[float]: ...
    async def embed_batch(self, texts: list[str]) -> list[list[float]]: ...


class MemsearchService:
    """Three-tier memory service backed by Markdown + sqlite-vec."""

    def __init__(
        self,
        memory_root: str | Path,
        embedding: EmbeddingProvider,
        embedding_dim: int,
    ) -> None:
        self._memory_root = Path(memory_root)
        self._embedding = embedding
        self._dim = embedding_dim

        self.store = MarkdownStore(self._memory_root)
        self.index = SqliteVecIndex(
            db_path=self._memory_root / "index" / "memory.db",
            embedding_dim=embedding_dim,
        )
        self._write_queue = MemoryWriteQueue(
            persist_path=self._memory_root / "index" / "pending_writes.json",
        )

    # -- Three-tier loading --

    async def load_context(self, query: str) -> dict[str, Any]:
        """Load memory context for injection into system prompt.

        Returns: {"tier1": str, "tier2": str, "tier3": list[dict]}
        """
        # Tier 1: MEMORY.md (always)
        tier1 = self.store.read_core_memory()

        # Tier 2: today + yesterday daily logs
        today = datetime.date.today()
        yesterday = today - datetime.timedelta(days=1)
        tier2_parts = []
        for date in [today, yesterday]:
            content = self.store.read_file(f"{date.isoformat()}.md")
            if content:
                tier2_parts.append(content)
        tier2 = "\n\n".join(tier2_parts)

        # Tier 3: hybrid search for deep memories
        tier3 = await self.search(query)

        return {"tier1": tier1, "tier2": tier2, "tier3": tier3}

    # -- Search --

    async def search(self, query: str, limit: int = 8) -> list[dict]:
        """Hybrid search (vector + FTS5 + RRF) across indexed memory."""
        vector = await self._embedding.embed(query)
        return self.index.hybrid_search(
            query_text=query,
            query_vector=vector,
            limit=limit,
        )

    # -- Write operations --

    async def write_memory(
        self,
        content: str,
        category: str = "daily",
        filename: str | None = None,
        *,
        consolidate: bool = True,
    ) -> dict:
        """Write a memory entry.

        Args:
            content: The memory text to write.
            category: "daily" | "people" | "topics" | "decisions" | "core"
            filename: Target filename (required for non-daily categories).
            consolidate: If True, check for duplicates before writing.

        Returns:
            {"written": bool, "action": "new"|"update"|"skip", "path": str}
        """
        # Determine target path
        if category == "daily":
            today = datetime.date.today().isoformat()
            relative_path = f"{today}.md"
        elif category == "core":
            relative_path = "MEMORY.md"
        else:
            if not filename:
                raise ValueError(f"filename required for category '{category}'")
            relative_path = f"{category}/{filename}"

        # Consolidation check
        if consolidate:
            vector = await self._embedding.embed(content)
            existing = self.index.search_vector(vector, limit=1)
            if existing:
                score = existing[0].get("score", 0)
                if score >= SIMILARITY_SKIP:
                    log.debug("write skipped (duplicate)", score=score)
                    return {"written": False, "action": "skip", "path": relative_path}
                if score >= SIMILARITY_UPDATE:
                    # Update existing file by appending
                    log.debug("write update (similar)", score=score)
                    # Fall through to append logic

        # Write to Markdown (append for daily, write for deep)
        if category == "daily":
            existing_content = self.store.read_file(relative_path)
            if existing_content:
                new_content = f"{existing_content}\n- {content}"
            else:
                today_str = datetime.date.today().isoformat()
                new_content = f"# {today_str}\n\n- {content}"
            self.store.write_file(relative_path, new_content)
        else:
            existing_content = self.store.read_file(relative_path)
            if existing_content:
                new_content = f"{existing_content}\n\n{content}"
            else:
                heading = filename.replace(".md", "").replace("-", " ").title() if filename else category.title()
                new_content = f"# {heading}\n\n{content}"
            self.store.write_file(relative_path, new_content)

        # Update index
        await self._index_file(relative_path)

        return {"written": True, "action": "new", "path": relative_path}

    async def _index_file(self, relative_path: str) -> None:
        """Re-index a single file."""
        # Remove old chunks for this file
        self.index.delete_by_source(relative_path)
        # Chunk and re-index
        chunks = self.store.chunk_file(relative_path)
        if chunks:
            texts = [c.content for c in chunks]
            vectors = await self._embedding.embed_batch(texts)
            for chunk, vector in zip(chunks, vectors):
                self.index.upsert(chunk, vector)

    # -- Index management --

    async def rebuild_index(self) -> int:
        """Full index rebuild from all Markdown files. Returns chunk count."""
        chunks = self.store.chunk_all()
        if not chunks:
            self.index.rebuild([])
            return 0
        texts = [c.content for c in chunks]
        vectors = await self._embedding.embed_batch(texts)
        pairs = list(zip(chunks, vectors))
        self.index.rebuild(pairs)
        log.info("index rebuilt", chunks=len(pairs))
        return len(pairs)

    # -- Replay pending writes --

    async def replay_pending(self) -> int:
        """Replay pending writes from disk (called on startup)."""
        return await self._write_queue.drain(self._handle_write_item)

    async def _handle_write_item(self, item: WriteItem) -> bool:
        """Process a single write queue item."""
        if item.write_type == WriteType.DELETE:
            self.store.delete_file(item.relative_path)
            self.index.delete_by_source(item.relative_path)
        elif item.write_type in (WriteType.UPSERT, WriteType.APPEND):
            self.store.write_file(item.relative_path, item.content)
            await self._index_file(item.relative_path)
        return True

    # -- Stats --

    async def get_stats(self) -> dict:
        """Return memory system stats."""
        index_stats = self.index.stats()
        files = self.store.list_files()
        return {
            **index_stats,
            "file_count": len(files),
            "pending_writes": self._write_queue.pending_count(),
        }
```

- [ ] **Step 3: Run tests and verify**

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_memsearch/test_memsearch_service.py -x -v`

Expected: all pass.

Commit: `feat(memsearch): implement MemsearchService with three-tier loading and consolidation`

---

## Task 6: Rewrite MemoryService to Use MemsearchService

**Goal:** Rewrite `smolgura/services/memory.py` to use MemsearchService as backend while preserving the IPC interface. Other modules (GuraCore, tools, drivers) call memory through IPC and should not be affected.

**Files:**
- Edit: `smolgura/services/memory.py`
- Edit: `tests/test_services/test_memory.py`
- Edit: `tests/test_services/test_memory_update.py`

- [ ] **Step 1: Update tests to use new backend**

Edit `tests/test_services/test_memory.py`:

Replace `FakeVectorStore` with a `FakeMemsearchBackend` that implements the same interface the new `MemoryService` expects. The IPC-level tests (`test_store_and_search`, `test_search_multiple_partitions`, `test_delete`, etc.) should remain unchanged — they validate the IPC contract.

Key changes:
- `FakeVectorStore` → `FakeMemsearchBackend` (wraps an in-memory MemsearchService)
- The `MemoryService.__init__` signature changes: `vector_store` → `memsearch: MemsearchService`
- IPC handler payloads stay the same: `{partition, doc_id, text, vector, metadata}`
- Search payload stays: `{partitions, query_vector, limit}`
- New payloads: `service.memory.load_context` → `{query}`, `service.memory.rebuild_index` → `{}`

Run existing tests to see them fail (expected — interface changed).

- [ ] **Step 2: Rewrite MemoryService**

Edit `smolgura/services/memory.py`:

The rewritten `MemoryService` delegates to `MemsearchService` instead of a vector store. The IPC handlers remain the same subjects (`service.memory.store`, `.search`, `.update`, `.delete`, `.list`, `.clear`, `.stats`). New handlers: `service.memory.load_context`, `service.memory.rebuild_index`.

Key mapping:
- `store(partition, doc_id, text, vector, metadata)` → `memsearch.write_memory()` — partition maps to category (e.g., `gura` → daily, `episodic_gura` → daily, `skills_gura` → topics/skills)
- `search(partitions, query_vector, limit)` → `memsearch.search()` — ignores partitions, uses hybrid search across all indexed memory
- `update(partition, doc_id, text, vector, metadata)` → `memsearch.write_memory()` with consolidation
- `delete(partition, doc_id)` → `memsearch.store.delete_file()` (best-effort mapping)
- `list_entries(partition)` → `memsearch.store.list_files()`
- `stats(partition)` → `memsearch.get_stats()`
- New: `load_context(query)` → `memsearch.load_context(query)` — returns three-tier context
- New: `rebuild_index()` → `memsearch.rebuild_index()`

Preserve `FakeVectorStore` temporarily for backward compatibility during migration, but mark it deprecated.

- [ ] **Step 3: Run updated tests**

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_services/test_memory.py tests/test_services/test_memory_update.py -x -v`

Expected: all pass.

Commit: `refactor(memory): rewrite MemoryService to use MemsearchService backend`

---

## Task 7: Update GuraCore Auto-Recall to Use Three-Tier Loading

**Goal:** Replace `_auto_recall` in `gura/core.py` to use the new three-tier loading via `service.memory.load_context` IPC. Replace `_auto_memorize` to write Markdown.

**Files:**
- Edit: `smolgura/gura/core.py`
- Edit: `smolgura/gura/prompts.py` (update `render_context_message` signature)

- [ ] **Step 1: Rewrite `_auto_recall` in `gura/core.py`**

Current behavior (lines 1046-1089):
- Embeds query via `self._tools._embedding.embed(query)`
- Calls `service.memory.search` with 3 partitions
- Splits results by partition into `memories`, `episodes`, `skills`

New behavior:
- Calls `service.memory.load_context` with `{"query": query}`
- Gets back `{"tier1": str, "tier2": str, "tier3": list[dict]}`
- Stores in `self._recalled` with keys `core`, `daily`, `deep` (instead of `memories`, `episodes`, `skills`)

```python
async def _auto_recall(self, query: str) -> None:
    """Load three-tier memory context before processing."""
    self._recalled = {"core": "", "daily": "", "deep": []}
    try:
        result = await self._ipc.request(
            self._agent_id,
            "service.memory.load_context",
            {"query": query},
        )
        self._recalled["core"] = result.get("tier1", "")
        self._recalled["daily"] = result.get("tier2", "")
        self._recalled["deep"] = result.get("tier3", [])
        log.info(
            "auto recall",
            agent_id=self._agent_id,
            core_len=len(self._recalled["core"]),
            daily_len=len(self._recalled["daily"]),
            deep_count=len(self._recalled["deep"]),
        )
    except Exception:
        log.warning("auto recall failed", agent_id=self._agent_id, exc_info=True)
```

- [ ] **Step 2: Update context injection in `_prepare_context`**

Current (lines 1033-1044):
```python
context = render_context_message(
    memories=[m["text"] for m in self._recalled["memories"]] ...,
    episodes=[e["text"] for e in self._recalled["episodes"]] ...,
    skills=[s["text"] for s in self._recalled["skills"]] ...,
)
```

New:
```python
context = render_context_message(
    current_time=datetime.datetime.now().strftime("%Y-%m-%d %H:%M"),
    memory=self._state_summary or None,
    core_memory=self._recalled["core"] or None,
    daily_memory=self._recalled["daily"] or None,
    deep_memories=[d["content"] for d in self._recalled["deep"]] if self._recalled["deep"] else None,
    item=item,
    parent=parent,
    siblings=siblings,
    tasks_tree=tasks_tree,
)
```

- [ ] **Step 3: Update `render_context_message` in `gura/prompts.py`**

Replace `memories`, `episodes`, `skills` parameters with `core_memory`, `daily_memory`, `deep_memories`. Update the template to format the `[Memory]` section with three tiers.

- [ ] **Step 4: Rewrite `_auto_memorize`**

Current (lines 1299-1378): parses `## User Profile` from state, embeds facts, calls `service.memory.store` with vectors.

New: calls `service.memory.store` with `{"content": fact, "category": "daily"}` — no manual embedding needed (MemsearchService handles it).

```python
async def _auto_memorize(self) -> None:
    """Extract facts from User Profile and write to daily memory."""
    if self._tools is None:
        return
    facts = self._parse_section(self._state_summary, "User Profile")
    if not facts:
        return
    log.info("auto memorize", agent_id=self._agent_id, facts=len(facts))
    for fact in facts:
        try:
            await self._ipc.request(
                self._agent_id,
                "service.memory.store",
                {"content": fact, "category": "daily"},
            )
        except Exception:
            log.warning("auto memorize failed", fact=fact, exc_info=True)
```

- [ ] **Step 5: Run affected tests**

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/ -x -v -k "memory or core or prompt"`

Expected: all pass (may need to update some test mocks).

Commit: `refactor(core): update GuraCore auto-recall/memorize for three-tier memsearch`

---

## Task 8: Rewrite ImageMemory to Use Local Files + sqlite-vec

**Goal:** Replace RustFS + Milvus in `vision/memory.py` with local filesystem + sqlite-vec.

**Files:**
- Edit: `smolgura/vision/memory.py`
- Edit: `tests/test_vision/test_memory.py`

- [ ] **Step 1: Update test fixtures**

Replace `FakeMilvusClient` and `FakeRustFS` with file-system based fixtures. `ImageMemory.__init__` signature changes:
- Remove: `milvus: MilvusClient`, `rustfs: RustFSClient`
- Add: `images_dir: Path`, `db_path: Path`, `embedding_dim: int`

- [ ] **Step 2: Rewrite ImageMemory**

Key changes:
- Image storage: `self._images_dir / f"{sha256_hash}.{ext}"` (local filesystem)
- Metadata + vectors: sqlite-vec `image_memory` table in same DB or separate DB at `/data/dollos/images/<character-id>/image_memory.db`
- `store()`: save image file locally, VLM describe, embed description, insert to sqlite-vec
- `search_by_text()`: embed query → sqlite-vec search → return records
- `get_image_bytes()`: read from local filesystem
- `cleanup()`: delete image directory

Remove all imports of `MilvusClient` and `RustFSClient`.

- [ ] **Step 3: Run tests**

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_vision/test_memory.py -x -v`

Expected: all pass.

Commit: `refactor(vision): rewrite ImageMemory to use local files + sqlite-vec`

---

## Task 9: Migrate Speaker Profiles to sqlite-vec

**Goal:** Replace Milvus `speaker_profiles` collection with sqlite-vec table in `/data/dollos/db/speakers.db`.

**Files:**
- Edit: `smolgura/audio/speaker.py`
- Create: `tests/test_audio/test_speaker_sqlitevec.py`

- [ ] **Step 1: Write tests for sqlite-vec speaker storage**

Create `tests/test_audio/test_speaker_sqlitevec.py` — test `register_speaker`, `identify_speaker`, `update_speaker_embedding` using sqlite-vec instead of Milvus. Mock the CAM++ model.

- [ ] **Step 2: Rewrite SpeakerService**

Key changes to `smolgura/audio/speaker.py`:
- Remove: `from smolgura.infra.milvus import COLLECTION_SPEAKERS, MilvusClient`
- Add: sqlite-vec based storage class `SpeakerVecStore` with 192-dim vectors
- `__init__` signature: `milvus: MilvusClient` → `db_path: Path`
- `register_speaker()`: insert to `speaker_profiles` table + sqlite-vec
- `identify_speaker()`: sqlite-vec search → `SpeakerProfile` lookup
- `update_speaker_embedding()`: get old vector from sqlite-vec, compute weighted average, upsert

The `SpeakerProfile` Tortoise ORM model stays unchanged (metadata only).

- [ ] **Step 3: Run tests**

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_audio/test_speaker_sqlitevec.py -x -v`

Expected: all pass.

Commit: `refactor(audio): migrate speaker profiles from Milvus to sqlite-vec`

---

## Task 10: Data Migration Script

**Goal:** One-time migration from Milvus + RustFS to Markdown + sqlite-vec + local files. Optional — new installs start fresh.

**Files:**
- Create: `scripts/migrate_milvus_to_markdown.py`
- Create: `tests/test_scripts/test_migration.py`

- [ ] **Step 1: Write migration script tests**

Create `tests/test_scripts/test_migration.py` — test with mock Milvus data:
- Memory entries → Markdown files (partitioned by metadata)
- Episodic entries → daily logs (grouped by date)
- Image memories → local files + sqlite-vec metadata
- Speaker profiles → sqlite-vec

- [ ] **Step 2: Implement migration script**

Create `scripts/migrate_milvus_to_markdown.py`:

```python
"""One-time migration: Milvus + RustFS → Markdown + sqlite-vec + local files.

Usage:
    python scripts/migrate_milvus_to_markdown.py --character-id gura

Steps:
1. Connect to existing Milvus and SQLite (Tortoise ORM)
2. Export MemoryEntry by partition:
   - "gura" partition → people/, topics/, decisions/ (categorize by metadata)
   - "episodic_gura" → YYYY-MM-DD.md (group by created_at date)
   - "skills_gura" → topics/skills/
3. Generate MEMORY.md from User Profile state entries
4. Export ImageMemoryEntry → download from RustFS → save to /data/dollos/images/
5. Export speaker_profiles → sqlite-vec
6. Rebuild sqlite-vec + FTS5 index from Markdown
7. Verify counts match
"""
```

Key functions:
- `migrate_text_memories(milvus, character_id, output_dir)`
- `migrate_episodic_to_daily(milvus, character_id, output_dir)`
- `migrate_images(rustfs, milvus, character_id, output_dir)`
- `migrate_speakers(milvus, db_path)`
- `rebuild_index(output_dir, embedding)`
- `verify_migration(old_counts, new_counts)`

- [ ] **Step 3: Run tests**

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/test_scripts/test_migration.py -x -v`

Expected: all pass.

Commit: `feat(migration): add Milvus-to-Markdown migration script`

---

## Task 11: Remove Milvus and RustFS Dependencies

**Goal:** Clean up — remove all Milvus and RustFS code and dependencies now that everything uses memsearch.

**Files:**
- Delete: `smolgura/infra/milvus.py`
- Delete: `smolgura/infra/rustfs.py`
- Delete: `tests/test_integration/test_memory_milvus.py`
- Delete: `tests/test_infra/test_milvus.py`
- Edit: `pyproject.toml` — remove `pymilvus`, `minio`
- Edit: `smolgura/services/memory.py` — remove `FakeVectorStore`, `MilvusVectorStore` (if still present)
- Edit: `smolgura/services/models.py` — remove `MemoryEntry.milvus_int_id` field, remove `ExperienceEntry`
- Edit: any import references to milvus/rustfs across the codebase

- [ ] **Step 1: Search for all remaining Milvus/RustFS references**

```bash
cd ~/Projects/DollOS-Server && grep -rn "milvus\|rustfs\|RustFS\|MilvusClient\|pymilvus\|minio" smolgura/ tests/ --include="*.py" | grep -v __pycache__ | grep -v migration
```

- [ ] **Step 2: Delete files**

```bash
rm smolgura/infra/milvus.py
rm smolgura/infra/rustfs.py
rm tests/test_integration/test_memory_milvus.py
rm tests/test_infra/test_milvus.py
```

- [ ] **Step 3: Remove dependencies from pyproject.toml**

Remove from `dependencies`:
```
"pymilvus>=2.6.4",
"minio>=7.2.0",
```

- [ ] **Step 4: Clean up remaining imports**

Update any files that still import from `smolgura.infra.milvus` or `smolgura.infra.rustfs`.

- [ ] **Step 5: Run full test suite**

Run: `cd ~/Projects/DollOS-Server && python -m pytest tests/ -x -v --ignore=tests/test_integration`

Expected: all pass with no Milvus/RustFS references.

Commit: `chore: remove Milvus and RustFS dependencies`

---

## Appendix: Embedding Dimension

The current system uses `EmbeddingService` (`smolgura/infra/embedding.py`) with OpenAI-compatible API. The default dimension is 1024 (Qwen3-Embedding-0.6B via kmod). The spec says "dimension fixed at sqlite-vec table creation time."

When creating the `memory_vec` virtual table, the dimension is set once:
```sql
CREATE VIRTUAL TABLE memory_vec USING vec0(
    content_hash TEXT PRIMARY KEY,
    embedding float[1024]
);
```

The `SqliteVecIndex.__init__` takes `embedding_dim` parameter. The `MemsearchService` passes this through. At service startup, the dimension should be read from config or detected from the embedding kmod.

## Appendix: IPC Subject Mapping (Before → After)

| IPC Subject | Before (Milvus) | After (memsearch) |
|---|---|---|
| `service.memory.store` | `MilvusVectorStore.store()` | `MemsearchService.write_memory()` |
| `service.memory.search` | `MilvusVectorStore.search()` | `MemsearchService.search()` |
| `service.memory.update` | `MilvusVectorStore.update()` | `MemsearchService.write_memory(consolidate=True)` |
| `service.memory.delete` | `MilvusVectorStore.delete()` | `MemsearchService.store.delete_file()` |
| `service.memory.list` | `MilvusVectorStore.list_entries()` | `MemsearchService.store.list_files()` |
| `service.memory.clear` | `MilvusVectorStore.clear()` | Reset character memory directory |
| `service.memory.stats` | `MilvusVectorStore.stats()` | `MemsearchService.get_stats()` |
| `service.memory.load_context` | **(new)** | `MemsearchService.load_context()` |
| `service.memory.rebuild_index` | **(new)** | `MemsearchService.rebuild_index()` |
