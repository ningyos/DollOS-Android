# DollOS-Server Character System (.doll) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add .doll character pack support — load, switch, list, delete, export characters. Integrate with personality system, TTS, and Web UI.

**Architecture:** CharacterManager handles .doll zip lifecycle. Personality from personality.json replaces hardcoded config. Voice settings sent to TTS kmod via NATS. 3D model served to Web UI. Per-character memory directories.

**Tech Stack:** Python, zipfile, NATS IPC, WebSocket, asyncio

---

## File Structure

### New files (under `smolgura/character/`)

```
smolgura/character/
  __init__.py
  models.py           — dataclasses: CharacterManifest, PersonalityConfig, VoiceConfig, SceneConfig
  validator.py        — CharacterValidator: zip-slip, size limits, required files
  manager.py          — CharacterManager: load, switch, list, delete, export, get_current
```

### Modified files

```
smolgura/gura/templates/core_identity.j2   — parameterize name, personality, speech, quirks
smolgura/gura/prompts.py                   — pass character personality to render_system_prompt()
smolgura/gura/core.py                      — accept CharacterManager, rebuild prompt on switch
smolgura/guraos.py                         — create CharacterManager at boot, wire into GuraCore
smolgura/desktop.py                        — handle character_changed WS notification, serve model.glb
smolgura/config.py                         — add CharacterConfig with data dir path
smolgura/gura/tools.py                     — register character.list / character.switch / character.info
```

### Test files

```
tests/character/
  test_models.py
  test_validator.py
  test_manager.py
  test_prompt_integration.py
  test_tools.py
```

---

## Constants & Paths

All character data lives under the DollOS data root (default `/data/dollos/`). The exact root is `config.system.data_dir` or falls back to `/data/dollos/`.

```
/data/dollos/
  characters/<character-id>/     ← extracted .doll contents
    manifest.json
    personality.json
    voice.json
    scene.json
    model.glb
    animations/
    thumbnail.png
    .installed_meta.json         ← server-side metadata (id, installedAt)
  memory/<character-id>/         ← per-character memory (Section 1 of redesign spec)
```

---

## Task 1: CharacterPack Data Classes

**Goal:** Define Python dataclasses mirroring the Android-side `CharacterPack.kt` — one-to-one field parity so `.doll` files are fully portable between phone and server.

**Files:**
- Create: `smolgura/character/__init__.py`
- Create: `smolgura/character/models.py`
- Create: `tests/character/__init__.py`
- Create: `tests/character/test_models.py`

- [ ] **Step 1: Create `smolgura/character/__init__.py`**

```python
"""DollOS Character Pack system."""
```

- [ ] **Step 2: Create `smolgura/character/models.py`**

```python
"""Data classes for .doll character pack contents."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class CharacterManifest:
    """manifest.json — character metadata."""

    format_version: int
    name: str
    version: str
    author: str
    description: str = ""
    wake_word: str | None = None
    avatar_type: str = "3d"  # "3d" or "live2d"
    created: str = ""

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> CharacterManifest:
        return cls(
            format_version=d["formatVersion"],
            name=d["name"],
            version=d["version"],
            author=d["author"],
            description=d.get("description", ""),
            wake_word=d.get("wakeWord"),
            avatar_type=d.get("avatarType", "3d"),
            created=d.get("created", ""),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "formatVersion": self.format_version,
            "name": self.name,
            "version": self.version,
            "author": self.author,
            "description": self.description,
            "wakeWord": self.wake_word,
            "avatarType": self.avatar_type,
            "created": self.created,
        }


@dataclass(frozen=True)
class PersonalityConfig:
    """personality.json — character personality for system prompt."""

    backstory: str = ""
    response_directive: str = ""
    dynamism: float = 0.5
    address: str = ""
    language_preference: str = ""

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> PersonalityConfig:
        return cls(
            backstory=d.get("backstory", ""),
            response_directive=d.get("responseDirective", ""),
            dynamism=float(d.get("dynamism", 0.5)),
            address=d.get("address", ""),
            language_preference=d.get("languagePreference", ""),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "backstory": self.backstory,
            "responseDirective": self.response_directive,
            "dynamism": self.dynamism,
            "address": self.address,
            "languagePreference": self.language_preference,
        }


@dataclass(frozen=True)
class VoiceConfig:
    """voice.json — TTS settings.

    Server-side extension: adds reference_audio and provider fields
    beyond Android's speed/pitch/ttsModel/language.
    """

    speed: float = 1.0
    pitch: float = 1.0
    tts_model: str = "default"
    language: str = ""
    # Server-side extensions (for fish-speech / local TTS kmod)
    provider: str = "local"
    reference_audio: str | None = None  # relative path inside .doll
    reference_transcript: str | None = None
    api_reference_id: str | None = None

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> VoiceConfig:
        return cls(
            speed=float(d.get("speed", 1.0)),
            pitch=float(d.get("pitch", 1.0)),
            tts_model=d.get("ttsModel", "default"),
            language=d.get("language", ""),
            provider=d.get("provider", "local"),
            reference_audio=d.get("referenceAudio"),
            reference_transcript=d.get("referenceTranscript"),
            api_reference_id=d.get("apiReferenceId"),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "speed": self.speed,
            "pitch": self.pitch,
            "ttsModel": self.tts_model,
            "language": self.language,
            "provider": self.provider,
            "referenceAudio": self.reference_audio,
            "referenceTranscript": self.reference_transcript,
            "apiReferenceId": self.api_reference_id,
        }


@dataclass(frozen=True)
class SceneConfig:
    """scene.json — 3D scene settings for Web UI."""

    background_type: str = "color"
    background_value: str = "#0a0a1a"
    ambient_light: float = 0.3
    directional_intensity: float = 1.0
    directional_direction: tuple[float, float, float] = (0.0, -1.0, -0.5)
    directional_color: str = "#ffffff"
    camera_position: tuple[float, float, float] = (0.0, 1.2, 3.0)
    camera_target: tuple[float, float, float] = (0.0, 1.0, 0.0)
    camera_fov: float = 45.0

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> SceneConfig:
        bg = d.get("background", {})
        lighting = d.get("lighting", {})
        dir_light = lighting.get("directional", {})
        camera = d.get("camera", {})
        return cls(
            background_type=bg.get("type", "color"),
            background_value=bg.get("value", "#0a0a1a"),
            ambient_light=float(lighting.get("ambient", 0.3)),
            directional_intensity=float(dir_light.get("intensity", 1.0)),
            directional_direction=tuple(dir_light.get("direction", [0.0, -1.0, -0.5])),
            directional_color=dir_light.get("color", "#ffffff"),
            camera_position=tuple(camera.get("position", [0.0, 1.2, 3.0])),
            camera_target=tuple(camera.get("target", [0.0, 1.0, 0.0])),
            camera_fov=float(camera.get("fov", 45.0)),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "background": {"type": self.background_type, "value": self.background_value},
            "lighting": {
                "ambient": self.ambient_light,
                "directional": {
                    "intensity": self.directional_intensity,
                    "direction": list(self.directional_direction),
                    "color": self.directional_color,
                },
            },
            "camera": {
                "position": list(self.camera_position),
                "target": list(self.camera_target),
                "fov": self.camera_fov,
            },
        }


@dataclass
class InstalledCharacter:
    """Runtime representation of an installed character."""

    id: str
    manifest: CharacterManifest
    personality: PersonalityConfig
    voice: VoiceConfig
    scene: SceneConfig
    directory: str  # absolute path to extracted character directory
    installed_at: float = 0.0  # unix timestamp

    def to_list_dict(self) -> dict[str, Any]:
        """Compact representation for character.list tool."""
        return {
            "id": self.id,
            "name": self.manifest.name,
            "author": self.manifest.author,
            "avatar_type": self.manifest.avatar_type,
            "description": self.manifest.description,
        }

    def to_detail_dict(self) -> dict[str, Any]:
        """Full representation for character.info tool."""
        return {
            "id": self.id,
            "manifest": self.manifest.to_dict(),
            "personality": self.personality.to_dict(),
            "voice": self.voice.to_dict(),
            "scene": self.scene.to_dict(),
            "installed_at": self.installed_at,
        }
```

- [ ] **Step 3: Create `tests/character/test_models.py`**

```python
"""Tests for character pack data classes."""

import pytest
from smolgura.character.models import (
    CharacterManifest,
    InstalledCharacter,
    PersonalityConfig,
    SceneConfig,
    VoiceConfig,
)


class TestCharacterManifest:
    def test_from_dict_roundtrip(self):
        d = {
            "formatVersion": 1,
            "name": "Luna",
            "version": "1.0.0",
            "author": "test",
            "description": "A test character",
            "wakeWord": "hey luna",
            "avatarType": "3d",
            "created": "2026-03-25T00:00:00Z",
        }
        m = CharacterManifest.from_dict(d)
        assert m.name == "Luna"
        assert m.format_version == 1
        assert m.to_dict() == d

    def test_from_dict_defaults(self):
        d = {"formatVersion": 1, "name": "X", "version": "1.0", "author": "a"}
        m = CharacterManifest.from_dict(d)
        assert m.avatar_type == "3d"
        assert m.wake_word is None
        assert m.description == ""


class TestPersonalityConfig:
    def test_from_dict_roundtrip(self):
        d = {
            "backstory": "Luna is cheerful",
            "responseDirective": "Speak warmly",
            "dynamism": 0.6,
            "address": "主人",
            "languagePreference": "zh-TW",
        }
        p = PersonalityConfig.from_dict(d)
        assert p.backstory == "Luna is cheerful"
        assert p.dynamism == pytest.approx(0.6)
        assert p.to_dict() == d

    def test_defaults(self):
        p = PersonalityConfig.from_dict({})
        assert p.backstory == ""
        assert p.dynamism == pytest.approx(0.5)


class TestVoiceConfig:
    def test_from_dict_with_server_extensions(self):
        d = {
            "speed": 1.1,
            "pitch": 0.9,
            "ttsModel": "fish-speech",
            "language": "zh-TW",
            "provider": "local",
            "referenceAudio": "voice/ref.wav",
        }
        v = VoiceConfig.from_dict(d)
        assert v.reference_audio == "voice/ref.wav"
        assert v.provider == "local"

    def test_android_compat(self):
        """Android voice.json only has speed/pitch/ttsModel/language."""
        d = {"speed": 1.0, "pitch": 1.0, "ttsModel": "default", "language": "zh-TW"}
        v = VoiceConfig.from_dict(d)
        assert v.provider == "local"
        assert v.reference_audio is None


class TestSceneConfig:
    def test_from_dict(self):
        d = {
            "background": {"type": "color", "value": "#000000"},
            "lighting": {
                "ambient": 0.5,
                "directional": {"intensity": 0.8, "direction": [0, -1, 0], "color": "#fff"},
            },
            "camera": {"position": [0, 1, 2], "target": [0, 0, 0], "fov": 60.0},
        }
        s = SceneConfig.from_dict(d)
        assert s.background_type == "color"
        assert s.camera_fov == pytest.approx(60.0)


class TestInstalledCharacter:
    def test_to_list_dict(self):
        m = CharacterManifest(1, "Luna", "1.0", "test", "desc", None, "3d", "")
        ic = InstalledCharacter(
            id="abc",
            manifest=m,
            personality=PersonalityConfig(),
            voice=VoiceConfig(),
            scene=SceneConfig(),
            directory="/tmp/abc",
        )
        ld = ic.to_list_dict()
        assert ld["id"] == "abc"
        assert ld["name"] == "Luna"
        assert "personality" not in ld
```

- [ ] **Step 4: Run tests, commit**

```bash
cd ~/Projects/DollOS-Server
python -m pytest tests/character/test_models.py -v
git add smolgura/character/__init__.py smolgura/character/models.py tests/character/__init__.py tests/character/test_models.py
git commit -m "feat(character): add CharacterPack data classes"
```

---

## Task 2: CharacterValidator

**Goal:** Validate `.doll` zip archives before extraction — zip-slip protection, size limits, required file checks. Mirrors Android `CharacterValidator.kt` logic.

**Files:**
- Create: `smolgura/character/validator.py`
- Create: `tests/character/test_validator.py`

- [ ] **Step 1: Create `smolgura/character/validator.py`**

```python
"""Validate .doll zip archives before extraction."""

from __future__ import annotations

import json
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from smolgura.character.models import CharacterManifest

# Limits matching Android CharacterValidator
MAX_DOLL_SIZE = 200 * 1024 * 1024        # 200 MB
MAX_ENTRY_SIZE = 100 * 1024 * 1024       # 100 MB per entry
MAX_TOTAL_EXTRACTED = 300 * 1024 * 1024  # 300 MB total
MAX_ENTRY_COUNT = 100
BACKSTORY_MAX_LEN = 2500
DIRECTIVE_MAX_LEN = 150


@dataclass
class ValidationResult:
    valid: bool
    error_code: str | None = None
    error_message: str | None = None
    manifest: CharacterManifest | None = None


class CharacterValidator:
    """Validate a .doll zip archive."""

    def validate(self, doll_path: str | Path) -> ValidationResult:
        """Validate the .doll file at the given path.

        Checks:
        1. Valid zip archive
        2. Entry count <= MAX_ENTRY_COUNT
        3. No path traversal (zip-slip: entries containing '..' or starting with '/')
        4. Individual entry size <= MAX_ENTRY_SIZE
        5. Total extracted size <= MAX_TOTAL_EXTRACTED
        6. manifest.json exists and is valid JSON with required fields
        7. formatVersion is known (== 1)
        8. model.glb exists for avatarType "3d"
        9. personality.json field lengths within limits
        """
        doll_path = Path(doll_path)

        if not doll_path.exists():
            return ValidationResult(False, "FILE_NOT_FOUND", f"File not found: {doll_path}")

        if doll_path.stat().st_size > MAX_DOLL_SIZE:
            return ValidationResult(False, "FILE_TOO_LARGE", f"File exceeds {MAX_DOLL_SIZE // (1024*1024)}MB")

        try:
            zf = zipfile.ZipFile(doll_path, "r")
        except (zipfile.BadZipFile, Exception) as e:
            return ValidationResult(False, "CORRUPT_ARCHIVE", f"Not a valid zip: {e}")

        with zf:
            entries = zf.infolist()

            # Entry count
            if len(entries) > MAX_ENTRY_COUNT:
                return ValidationResult(False, "TOO_MANY_ENTRIES", f"Archive has {len(entries)} entries (max {MAX_ENTRY_COUNT})")

            total_size = 0
            entry_names: set[str] = set()
            manifest_data: dict[str, Any] | None = None

            for entry in entries:
                name = entry.filename

                # Path traversal (zip-slip)
                if ".." in name or name.startswith("/"):
                    return ValidationResult(False, "PATH_TRAVERSAL", f"Dangerous path: {name}")

                # Entry size
                if entry.file_size > MAX_ENTRY_SIZE:
                    return ValidationResult(
                        False, "ENTRY_TOO_LARGE",
                        f"{name} is {entry.file_size // (1024*1024)}MB (max {MAX_ENTRY_SIZE // (1024*1024)}MB)",
                    )

                total_size += entry.file_size
                if total_size > MAX_TOTAL_EXTRACTED:
                    return ValidationResult(
                        False, "ARCHIVE_TOO_LARGE",
                        f"Total size exceeds {MAX_TOTAL_EXTRACTED // (1024*1024)}MB",
                    )

                entry_names.add(name)

                # Read manifest.json
                if name == "manifest.json":
                    try:
                        manifest_data = json.loads(zf.read(name))
                    except (json.JSONDecodeError, Exception) as e:
                        return ValidationResult(False, "INVALID_MANIFEST", f"Bad manifest.json: {e}")

            # Must have manifest.json
            if manifest_data is None:
                return ValidationResult(False, "NO_MANIFEST", "manifest.json not found")

            # Parse manifest
            try:
                manifest = CharacterManifest.from_dict(manifest_data)
            except (KeyError, Exception) as e:
                return ValidationResult(False, "INVALID_MANIFEST", f"Missing required field: {e}")

            # Format version
            if manifest.format_version != 1:
                return ValidationResult(False, "UNKNOWN_FORMAT", f"Unknown format version: {manifest.format_version}")

            # model.glb required for 3d
            if manifest.avatar_type == "3d" and "model.glb" not in entry_names:
                return ValidationResult(False, "MISSING_MODEL", "model.glb required for 3d avatar type")

            # Personality field lengths
            if "personality.json" in entry_names:
                try:
                    p = json.loads(zf.read("personality.json"))
                    backstory = p.get("backstory", "")
                    directive = p.get("responseDirective", "")
                    if len(backstory) > BACKSTORY_MAX_LEN:
                        return ValidationResult(
                            False, "BACKSTORY_TOO_LONG",
                            f"backstory is {len(backstory)} chars (max {BACKSTORY_MAX_LEN})",
                        )
                    if len(directive) > DIRECTIVE_MAX_LEN:
                        return ValidationResult(
                            False, "DIRECTIVE_TOO_LONG",
                            f"responseDirective is {len(directive)} chars (max {DIRECTIVE_MAX_LEN})",
                        )
                except (json.JSONDecodeError, Exception):
                    return ValidationResult(False, "INVALID_PERSONALITY", "Bad personality.json")

        return ValidationResult(True, manifest=manifest)
```

- [ ] **Step 2: Create `tests/character/test_validator.py`**

Write tests that create temporary `.doll` zip files with:
- Valid minimal pack (manifest.json + model.glb) -> passes
- Missing manifest.json -> `NO_MANIFEST`
- Path traversal entry (`../etc/passwd`) -> `PATH_TRAVERSAL`
- Unknown format version -> `UNKNOWN_FORMAT`
- Missing model.glb for 3d type -> `MISSING_MODEL`
- Oversized backstory -> `BACKSTORY_TOO_LONG`
- Entry count > 100 -> `TOO_MANY_ENTRIES`

```python
"""Tests for CharacterValidator."""

import json
import zipfile
from pathlib import Path

import pytest

from smolgura.character.validator import CharacterValidator


def _make_manifest(**overrides) -> dict:
    base = {
        "formatVersion": 1,
        "name": "Test",
        "version": "1.0",
        "author": "tester",
        "description": "test char",
        "avatarType": "3d",
    }
    base.update(overrides)
    return base


def _make_doll(tmp_path: Path, files: dict[str, bytes], name: str = "test.doll") -> Path:
    """Create a .doll zip with given files (name -> content bytes)."""
    doll_path = tmp_path / name
    with zipfile.ZipFile(doll_path, "w") as zf:
        for fname, content in files.items():
            zf.writestr(fname, content)
    return doll_path


@pytest.fixture
def validator():
    return CharacterValidator()


class TestCharacterValidator:
    def test_valid_minimal(self, tmp_path, validator):
        files = {
            "manifest.json": json.dumps(_make_manifest()).encode(),
            "model.glb": b"\x00" * 100,
        }
        result = validator.validate(_make_doll(tmp_path, files))
        assert result.valid
        assert result.manifest is not None
        assert result.manifest.name == "Test"

    def test_no_manifest(self, tmp_path, validator):
        result = validator.validate(_make_doll(tmp_path, {"model.glb": b"\x00"}))
        assert not result.valid
        assert result.error_code == "NO_MANIFEST"

    def test_path_traversal(self, tmp_path, validator):
        files = {
            "manifest.json": json.dumps(_make_manifest()).encode(),
            "../evil.txt": b"pwned",
            "model.glb": b"\x00",
        }
        result = validator.validate(_make_doll(tmp_path, files))
        assert not result.valid
        assert result.error_code == "PATH_TRAVERSAL"

    def test_unknown_format_version(self, tmp_path, validator):
        files = {
            "manifest.json": json.dumps(_make_manifest(formatVersion=99)).encode(),
            "model.glb": b"\x00",
        }
        result = validator.validate(_make_doll(tmp_path, files))
        assert not result.valid
        assert result.error_code == "UNKNOWN_FORMAT"

    def test_missing_model_for_3d(self, tmp_path, validator):
        files = {
            "manifest.json": json.dumps(_make_manifest(avatarType="3d")).encode(),
        }
        result = validator.validate(_make_doll(tmp_path, files))
        assert not result.valid
        assert result.error_code == "MISSING_MODEL"

    def test_backstory_too_long(self, tmp_path, validator):
        personality = {"backstory": "x" * 3000, "responseDirective": "ok"}
        files = {
            "manifest.json": json.dumps(_make_manifest()).encode(),
            "model.glb": b"\x00",
            "personality.json": json.dumps(personality).encode(),
        }
        result = validator.validate(_make_doll(tmp_path, files))
        assert not result.valid
        assert result.error_code == "BACKSTORY_TOO_LONG"

    def test_too_many_entries(self, tmp_path, validator):
        files = {"manifest.json": json.dumps(_make_manifest()).encode(), "model.glb": b"\x00"}
        for i in range(110):
            files[f"extra/{i}.txt"] = b"x"
        result = validator.validate(_make_doll(tmp_path, files))
        assert not result.valid
        assert result.error_code == "TOO_MANY_ENTRIES"

    def test_file_not_found(self, validator):
        result = validator.validate("/nonexistent/test.doll")
        assert not result.valid
        assert result.error_code == "FILE_NOT_FOUND"
```

- [ ] **Step 3: Run tests, commit**

```bash
cd ~/Projects/DollOS-Server
python -m pytest tests/character/test_validator.py -v
git add smolgura/character/validator.py tests/character/test_validator.py
git commit -m "feat(character): add CharacterValidator with zip-slip protection"
```

---

## Task 3: CharacterManager

**Goal:** Core lifecycle manager — load `.doll` files, switch active character, list/delete/export, get current. Mirrors Android `CharacterManager.kt` but adapted for server paths and async NATS notifications.

**Files:**
- Create: `smolgura/character/manager.py`
- Create: `tests/character/test_manager.py`

- [ ] **Step 1: Create `smolgura/character/manager.py`**

```python
"""CharacterManager — .doll lifecycle: load, switch, list, delete, export."""

from __future__ import annotations

import json
import shutil
import time
import uuid
import zipfile
from pathlib import Path
from typing import Any, Callable, Coroutine

import structlog

from smolgura.character.models import (
    CharacterManifest,
    InstalledCharacter,
    PersonalityConfig,
    SceneConfig,
    VoiceConfig,
)
from smolgura.character.validator import CharacterValidator

log = structlog.get_logger()

# Default data root (overridden by config)
DEFAULT_DATA_DIR = Path("/data/dollos")


class CharacterManager:
    """Manages .doll character pack lifecycle.

    Thread safety: all public methods are sync. Notification callbacks
    (on_character_switched) are async and awaited by the caller.
    """

    def __init__(
        self,
        data_dir: Path = DEFAULT_DATA_DIR,
        on_character_switched: Callable[[InstalledCharacter], Coroutine[Any, Any, None]] | None = None,
    ) -> None:
        self._data_dir = data_dir
        self._characters_dir = data_dir / "characters"
        self._memory_dir = data_dir / "memory"
        self._characters_dir.mkdir(parents=True, exist_ok=True)
        self._validator = CharacterValidator()

        # Runtime state
        self._active_id: str | None = None
        self._cache: dict[str, InstalledCharacter] = {}
        self.on_character_switched = on_character_switched

        # Load active ID from state file
        self._state_file = data_dir / "config" / "active_character.json"
        self._load_state()

        # Pre-populate cache
        self._scan_installed()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def load(self, doll_path: str | Path) -> InstalledCharacter | None:
        """Import a .doll file: validate, extract, return InstalledCharacter.

        Does NOT automatically switch to the new character.
        """
        doll_path = Path(doll_path)
        result = self._validator.validate(doll_path)
        if not result.valid:
            log.error("character validation failed", path=str(doll_path), code=result.error_code, msg=result.error_message)
            return None

        character_id = str(uuid.uuid4())
        char_dir = self._characters_dir / character_id
        char_dir.mkdir(parents=True, exist_ok=True)

        # Extract with zip-slip protection
        try:
            with zipfile.ZipFile(doll_path, "r") as zf:
                for entry in zf.infolist():
                    if entry.is_dir():
                        (char_dir / entry.filename).mkdir(parents=True, exist_ok=True)
                        continue
                    target = (char_dir / entry.filename).resolve()
                    if not str(target).startswith(str(char_dir.resolve())):
                        log.error("zip-slip detected during extraction", entry=entry.filename)
                        shutil.rmtree(char_dir, ignore_errors=True)
                        return None
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with zf.open(entry) as src, open(target, "wb") as dst:
                        shutil.copyfileobj(src, dst)
        except Exception as e:
            log.error("extraction failed", error=str(e))
            shutil.rmtree(char_dir, ignore_errors=True)
            return None

        # Write server-side metadata
        meta = {"id": character_id, "installedAt": time.time()}
        (char_dir / ".installed_meta.json").write_text(json.dumps(meta))

        # Create per-character memory directory
        mem_dir = self._memory_dir / character_id
        mem_dir.mkdir(parents=True, exist_ok=True)

        # Load and cache
        character = self._load_character(character_id)
        if character is None:
            shutil.rmtree(char_dir, ignore_errors=True)
            return None

        self._cache[character_id] = character
        log.info("character loaded", id=character_id, name=character.manifest.name)
        return character

    def switch(self, character_id: str) -> InstalledCharacter | None:
        """Switch active character. Returns the character or None if not found."""
        character = self._cache.get(character_id) or self._load_character(character_id)
        if character is None:
            log.error("character not found for switch", id=character_id)
            return None

        self._active_id = character_id
        self._cache[character_id] = character
        self._save_state()
        log.info("character switched", id=character_id, name=character.manifest.name)
        return character

    def list(self) -> list[InstalledCharacter]:
        """List all installed characters."""
        self._scan_installed()
        return list(self._cache.values())

    def delete(self, character_id: str) -> bool:
        """Delete a character and its memory directory. Returns True if deleted."""
        char_dir = self._characters_dir / character_id
        if not char_dir.exists():
            return False

        shutil.rmtree(char_dir, ignore_errors=True)
        self._cache.pop(character_id, None)

        if self._active_id == character_id:
            self._active_id = None
            self._save_state()

        log.info("character deleted", id=character_id)
        return True

    def export(self, character_id: str, output_path: str | Path) -> bool:
        """Export character as .doll zip (without memory or .installed_meta.json)."""
        char_dir = self._characters_dir / character_id
        if not char_dir.exists():
            return False

        output_path = Path(output_path)
        with zipfile.ZipFile(output_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for file in char_dir.rglob("*"):
                if file.is_file() and file.name != ".installed_meta.json":
                    arcname = file.relative_to(char_dir)
                    zf.write(file, arcname)

        log.info("character exported", id=character_id, path=str(output_path))
        return True

    def get_current(self) -> InstalledCharacter | None:
        """Get the currently active character, or None."""
        if self._active_id is None:
            return None
        return self._cache.get(self._active_id) or self._load_character(self._active_id)

    def get_character(self, character_id: str) -> InstalledCharacter | None:
        """Get a specific character by ID."""
        return self._cache.get(character_id) or self._load_character(character_id)

    def get_character_dir(self, character_id: str) -> Path | None:
        """Get the directory path for a character."""
        d = self._characters_dir / character_id
        return d if d.exists() else None

    @property
    def active_id(self) -> str | None:
        return self._active_id

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _load_character(self, character_id: str) -> InstalledCharacter | None:
        """Load character data from disk."""
        char_dir = self._characters_dir / character_id
        if not char_dir.exists():
            return None

        manifest_path = char_dir / "manifest.json"
        if not manifest_path.exists():
            return None

        try:
            manifest = CharacterManifest.from_dict(json.loads(manifest_path.read_text()))
        except Exception as e:
            log.warning("bad manifest", id=character_id, error=str(e))
            return None

        # Optional files — use defaults if missing
        personality = PersonalityConfig()
        personality_path = char_dir / "personality.json"
        if personality_path.exists():
            try:
                personality = PersonalityConfig.from_dict(json.loads(personality_path.read_text()))
            except Exception:
                pass

        voice = VoiceConfig()
        voice_path = char_dir / "voice.json"
        if voice_path.exists():
            try:
                voice = VoiceConfig.from_dict(json.loads(voice_path.read_text()))
            except Exception:
                pass

        scene = SceneConfig()
        scene_path = char_dir / "scene.json"
        if scene_path.exists():
            try:
                scene = SceneConfig.from_dict(json.loads(scene_path.read_text()))
            except Exception:
                pass

        # Metadata
        installed_at = 0.0
        meta_path = char_dir / ".installed_meta.json"
        if meta_path.exists():
            try:
                meta = json.loads(meta_path.read_text())
                installed_at = meta.get("installedAt", 0.0)
            except Exception:
                pass

        return InstalledCharacter(
            id=character_id,
            manifest=manifest,
            personality=personality,
            voice=voice,
            scene=scene,
            directory=str(char_dir),
            installed_at=installed_at,
        )

    def _scan_installed(self) -> None:
        """Scan characters directory and populate cache."""
        if not self._characters_dir.exists():
            return
        seen: set[str] = set()
        for d in self._characters_dir.iterdir():
            if d.is_dir() and (d / "manifest.json").exists():
                cid = d.name
                seen.add(cid)
                if cid not in self._cache:
                    char = self._load_character(cid)
                    if char is not None:
                        self._cache[cid] = char
        # Remove stale cache entries
        for stale in set(self._cache.keys()) - seen:
            del self._cache[stale]

    def _load_state(self) -> None:
        """Load active character ID from disk."""
        if self._state_file.exists():
            try:
                data = json.loads(self._state_file.read_text())
                self._active_id = data.get("active_character_id")
            except Exception:
                self._active_id = None

    def _save_state(self) -> None:
        """Persist active character ID to disk."""
        self._state_file.parent.mkdir(parents=True, exist_ok=True)
        self._state_file.write_text(json.dumps({"active_character_id": self._active_id}))
```

- [ ] **Step 2: Create `tests/character/test_manager.py`**

Write tests covering:
- `load()` with valid `.doll` -> character in cache, memory dir created
- `load()` with invalid `.doll` -> returns None
- `switch()` -> active_id updated, state file written
- `list()` -> returns all loaded characters
- `delete()` -> directory removed, cache cleared, active cleared if active
- `export()` -> creates valid `.doll` without `.installed_meta.json`
- `get_current()` -> returns active character or None
- State persistence: create manager, load+switch, create new manager instance from same dir -> active_id restored

```python
"""Tests for CharacterManager."""

import json
import zipfile
from pathlib import Path

import pytest

from smolgura.character.manager import CharacterManager


def _make_doll(tmp_path: Path, name: str = "test.doll", char_name: str = "Luna") -> Path:
    """Create a minimal valid .doll file."""
    doll_path = tmp_path / name
    manifest = {
        "formatVersion": 1,
        "name": char_name,
        "version": "1.0",
        "author": "tester",
        "avatarType": "3d",
    }
    personality = {
        "backstory": "A cheerful companion",
        "responseDirective": "Be warm",
        "dynamism": 0.7,
        "address": "主人",
        "languagePreference": "zh-TW",
    }
    voice = {"speed": 1.0, "pitch": 1.0, "ttsModel": "default", "language": "zh-TW"}
    with zipfile.ZipFile(doll_path, "w") as zf:
        zf.writestr("manifest.json", json.dumps(manifest))
        zf.writestr("personality.json", json.dumps(personality))
        zf.writestr("voice.json", json.dumps(voice))
        zf.writestr("model.glb", b"\x00" * 100)
    return doll_path


@pytest.fixture
def data_dir(tmp_path):
    return tmp_path / "dollos"


@pytest.fixture
def mgr(data_dir):
    return CharacterManager(data_dir=data_dir)


class TestCharacterManager:
    def test_load_valid(self, tmp_path, data_dir, mgr):
        doll = _make_doll(tmp_path)
        char = mgr.load(doll)
        assert char is not None
        assert char.manifest.name == "Luna"
        assert (Path(data_dir) / "characters" / char.id / "manifest.json").exists()
        assert (Path(data_dir) / "memory" / char.id).is_dir()

    def test_load_invalid(self, tmp_path, mgr):
        bad = tmp_path / "bad.doll"
        bad.write_bytes(b"not a zip")
        assert mgr.load(bad) is None

    def test_switch(self, tmp_path, data_dir, mgr):
        char = mgr.load(_make_doll(tmp_path))
        result = mgr.switch(char.id)
        assert result is not None
        assert mgr.active_id == char.id
        assert mgr.get_current().id == char.id

    def test_switch_nonexistent(self, mgr):
        assert mgr.switch("nonexistent") is None

    def test_list(self, tmp_path, mgr):
        mgr.load(_make_doll(tmp_path, "a.doll", "Alpha"))
        mgr.load(_make_doll(tmp_path, "b.doll", "Beta"))
        chars = mgr.list()
        names = {c.manifest.name for c in chars}
        assert "Alpha" in names
        assert "Beta" in names

    def test_delete(self, tmp_path, data_dir, mgr):
        char = mgr.load(_make_doll(tmp_path))
        mgr.switch(char.id)
        assert mgr.delete(char.id)
        assert mgr.active_id is None
        assert not (Path(data_dir) / "characters" / char.id).exists()
        assert mgr.get_current() is None

    def test_delete_nonexistent(self, mgr):
        assert not mgr.delete("fake-id")

    def test_export(self, tmp_path, mgr):
        char = mgr.load(_make_doll(tmp_path))
        out = tmp_path / "exported.doll"
        assert mgr.export(char.id, out)
        assert out.exists()
        with zipfile.ZipFile(out) as zf:
            names = zf.namelist()
            assert "manifest.json" in names
            assert ".installed_meta.json" not in names

    def test_state_persistence(self, tmp_path, data_dir):
        mgr1 = CharacterManager(data_dir=data_dir)
        char = mgr1.load(_make_doll(tmp_path))
        mgr1.switch(char.id)
        # New instance should restore state
        mgr2 = CharacterManager(data_dir=data_dir)
        assert mgr2.active_id == char.id
        assert mgr2.get_current() is not None
        assert mgr2.get_current().manifest.name == "Luna"
```

- [ ] **Step 3: Run tests, commit**

```bash
cd ~/Projects/DollOS-Server
python -m pytest tests/character/test_manager.py -v
git add smolgura/character/manager.py tests/character/test_manager.py
git commit -m "feat(character): add CharacterManager with load/switch/list/delete/export"
```

---

## Task 4: Integrate with System Prompt

**Goal:** Replace the hardcoded `core_identity.j2` personality with dynamic fields from `personality.json`. When a character is active, the system prompt uses its backstory, directive, name, speech style, and language. When no character is active, fall back to the current Gura defaults.

**Files:**
- Modify: `smolgura/gura/templates/core_identity.j2`
- Modify: `smolgura/gura/prompts.py`
- Modify: `smolgura/gura/core.py`
- Create: `tests/character/test_prompt_integration.py`

- [ ] **Step 1: Modify `core_identity.j2` to accept character variables**

Replace the hardcoded `[Gura]` identity block with Jinja2 conditionals:

```jinja2
{% if character %}
[{{ character.name }}]
Name: {{ character.name }}
{{ character.backstory }}

{% if character.response_directive %}
Response style: {{ character.response_directive }}
{% endif %}
{% if character.address %}
Address user as: {{ character.address }}
{% endif %}
{% if character.language_preference %}
Preferred language: {{ character.language_preference }}
{% endif %}
{% else %}
{# Default Gura identity — existing content unchanged #}
[Gura]
Name: Gura
Species: Digital life
Personality: Chill, blunt, a bit lazy. ...
...
{% endif %}
```

Keep the entire existing Gura block inside the `{% else %}` branch verbatim — do not modify a single character of the current default. The new `{% if character %}` branch takes `character` as a dict with keys: `name`, `backstory`, `response_directive`, `address`, `language_preference`.

- [ ] **Step 2: Modify `render_system_prompt()` in `prompts.py`**

Add optional `character: dict | None = None` parameter:

```python
def render_system_prompt(
    *,
    current_time: str,
    platform: str = "terminal",
    is_master: bool = True,
    has_vision: bool = False,
    has_image_memory: bool = False,
    tool_signatures: str = "(none)",
    character: dict | None = None,  # NEW
) -> str:
    ctx = {
        "current_time": current_time,
        "is_master": is_master,
        "has_vision": has_vision,
        "tool_signatures": tool_signatures,
        "character": character,  # NEW — None means use default Gura identity
    }
    # ... rest unchanged
```

- [ ] **Step 3: Modify `GuraCore._set_system_prompt()` in `core.py`**

Add a `_character_manager` field to `GuraCore.__init__`. In `_set_system_prompt`, read the active character's personality and pass it to `render_system_prompt`:

```python
# In GuraCore.__init__, add parameter:
#   character_manager: CharacterManager | None = None
# Store as self._character_manager

def _set_system_prompt(self, item=None, *, platform=None):
    # ... existing code up to render_system_prompt call ...

    character_dict = None
    if self._character_manager is not None:
        current = self._character_manager.get_current()
        if current is not None:
            character_dict = {
                "name": current.manifest.name,
                "backstory": current.personality.backstory,
                "response_directive": current.personality.response_directive,
                "address": current.personality.address,
                "language_preference": current.personality.language_preference,
            }

    prompt = render_system_prompt(
        current_time=...,
        platform=...,
        is_master=...,
        has_vision=...,
        has_image_memory=...,
        tool_signatures=...,
        character=character_dict,  # NEW
    )
    self._loop.set_system_message(prompt)
```

Also map `dynamism` to LLM temperature. Add to `_set_system_prompt` or to `AgentLoop`:

```python
if current is not None:
    self._loop.set_temperature(current.personality.dynamism)
```

- [ ] **Step 4: Create `tests/character/test_prompt_integration.py`**

```python
"""Tests for character personality -> system prompt integration."""

from smolgura.gura.prompts import render_system_prompt


class TestCharacterPrompt:
    def test_default_gura_identity(self):
        """No character -> falls back to hardcoded Gura."""
        prompt = render_system_prompt(current_time="2026-03-25 12:00")
        assert "[Gura]" in prompt
        assert "Chill, blunt" in prompt

    def test_character_identity(self):
        """Active character -> uses character personality."""
        char = {
            "name": "Luna",
            "backstory": "Luna is a cheerful companion who loves music.",
            "response_directive": "Speak warmly and concisely",
            "address": "主人",
            "language_preference": "zh-TW",
        }
        prompt = render_system_prompt(current_time="2026-03-25 12:00", character=char)
        assert "[Luna]" in prompt
        assert "cheerful companion" in prompt
        assert "Speak warmly" in prompt
        assert "主人" in prompt
        assert "[Gura]" not in prompt

    def test_character_minimal(self):
        """Character with only name and backstory."""
        char = {"name": "Min", "backstory": "Minimal character.", "response_directive": "", "address": "", "language_preference": ""}
        prompt = render_system_prompt(current_time="2026-03-25 12:00", character=char)
        assert "[Min]" in prompt
        assert "Minimal character" in prompt
```

- [ ] **Step 5: Run tests, commit**

```bash
cd ~/Projects/DollOS-Server
python -m pytest tests/character/test_prompt_integration.py -v
git add smolgura/gura/templates/core_identity.j2 smolgura/gura/prompts.py smolgura/gura/core.py tests/character/test_prompt_integration.py
git commit -m "feat(character): integrate personality.json with system prompt"
```

---

## Task 5: Integrate with TTS Kmod

**Goal:** On character switch, send the new character's voice settings to the TTS kmod via NATS. The TTS kmod reads `voice.json` fields to update its voice (reference audio, provider, speed, etc.).

**Files:**
- Modify: `smolgura/character/manager.py` (add NATS notification)
- Modify: `smolgura/guraos.py` (wire NATS client into CharacterManager)

- [ ] **Step 1: Add `_notify_tts` method to CharacterManager**

```python
async def notify_tts_switch(self, character: InstalledCharacter, nats_client: Any) -> None:
    """Notify TTS kmod of voice config change via NATS."""
    voice = character.voice
    payload = {
        "character_id": character.id,
        "character_name": character.manifest.name,
        "provider": voice.provider,
        "speed": voice.speed,
        "pitch": voice.pitch,
        "tts_model": voice.tts_model,
        "language": voice.language,
    }

    # If character has a reference audio file, resolve its absolute path
    if voice.reference_audio:
        ref_path = Path(character.directory) / voice.reference_audio
        if ref_path.exists():
            payload["reference_audio"] = str(ref_path)

    if voice.reference_transcript:
        payload["reference_transcript"] = voice.reference_transcript

    if voice.api_reference_id:
        payload["api_reference_id"] = voice.api_reference_id

    try:
        await nats_client.publish("kmod.tts.voice_config", json.dumps(payload).encode())
        log.info("tts voice config published", character=character.manifest.name)
    except Exception as e:
        log.warning("failed to publish tts voice config", error=str(e))
```

This method is async and called from the GuraOS-level switch handler (Task 9), not from the sync `switch()` method itself.

- [ ] **Step 2: Define NATS subject convention**

The TTS kmod should subscribe to `kmod.tts.voice_config` and update its reference audio / provider on receiving this message. Document this contract in a comment at the top of the method.

No changes to the TTS kmod itself in this plan — the kmod team handles that. This plan only publishes the notification.

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/DollOS-Server
git add smolgura/character/manager.py
git commit -m "feat(character): add TTS voice config NATS notification on switch"
```

---

## Task 6: Integrate with Desktop Web UI

**Goal:** On character switch, notify all connected Web UI clients via WebSocket to reload the 3D model. Add an HTTP endpoint to serve `model.glb` and other character assets.

**Files:**
- Modify: `smolgura/desktop.py`

- [ ] **Step 1: Add character asset HTTP endpoint**

Add a route to `DesktopDriver.__init__` for serving character assets:

```python
self._app.router.add_get("/api/character/asset/{path:.*}", self._character_asset_handler)
```

Implementation:

```python
async def _character_asset_handler(self, request: web.Request) -> web.Response:
    """Serve character assets (model.glb, animations/, thumbnail.png)."""
    if self._character_manager is None:
        return web.Response(status=503, text="Character system not available")

    current = self._character_manager.get_current()
    if current is None:
        return web.Response(status=404, text="No active character")

    asset_path = request.match_info.get("path", "")
    char_dir = Path(current.directory)
    file_path = (char_dir / asset_path).resolve()

    # Path traversal protection
    if not str(file_path).startswith(str(char_dir.resolve())):
        return web.Response(status=403)

    if not file_path.is_file():
        return web.Response(status=404)

    return web.FileResponse(file_path)
```

- [ ] **Step 2: Add character_changed WebSocket broadcast**

Add a method that broadcasts `character_changed` to all authenticated WS clients:

```python
async def broadcast_character_changed(self, character: InstalledCharacter) -> None:
    """Notify all connected Web UI clients that the character has changed."""
    await self.broadcast({
        "type": "character_changed",
        "data": {
            "id": character.id,
            "name": character.manifest.name,
            "avatar_type": character.manifest.avatar_type,
            "scene": character.scene.to_dict(),
            "has_model": Path(character.directory, "model.glb").exists(),
        },
    })
```

- [ ] **Step 3: Wire `_character_manager` into DesktopDriver**

Add `character_manager` parameter to `DesktopDriver.__init__`:

```python
def __init__(self, config, ipc, ..., character_manager=None):
    self._character_manager = character_manager
```

- [ ] **Step 4: Commit**

```bash
cd ~/Projects/DollOS-Server
git add smolgura/desktop.py
git commit -m "feat(character): add character asset endpoint and WS notification to desktop"
```

---

## Task 7: Per-Character Memory Directory

**Goal:** On character load, create the memory directory structure defined in the redesign spec Section 1. On character switch, the memory service reads from the new character's directory.

**Files:**
- Modify: `smolgura/character/manager.py` (already creates `memory/<id>/` in Task 3)

- [ ] **Step 1: Enhance memory directory creation in `load()`**

After creating `mem_dir = self._memory_dir / character_id`, also create the subdirectory structure:

```python
# Create per-character memory directory structure
mem_dir = self._memory_dir / character_id
mem_dir.mkdir(parents=True, exist_ok=True)
(mem_dir / "people").mkdir(exist_ok=True)
(mem_dir / "topics").mkdir(exist_ok=True)
(mem_dir / "decisions").mkdir(exist_ok=True)
(mem_dir / "index").mkdir(exist_ok=True)

# Seed MEMORY.md with character name
memory_md = mem_dir / "MEMORY.md"
if not memory_md.exists():
    manifest = CharacterManifest.from_dict(json.loads((char_dir / "manifest.json").read_text()))
    memory_md.write_text(
        f"# {manifest.name} — Memory\n\n"
        f"## Awareness\n\n(New character, no memories yet.)\n\n"
        f"## User Profile\n\n(none)\n"
    )
```

- [ ] **Step 2: Add `get_memory_dir()` method**

```python
def get_memory_dir(self, character_id: str | None = None) -> Path | None:
    """Get memory directory for a character (default: active character)."""
    cid = character_id or self._active_id
    if cid is None:
        return None
    d = self._memory_dir / cid
    return d if d.exists() else None
```

This will be used by memsearch (Section 1 implementation, separate plan) to locate the correct memory directory on character switch.

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/DollOS-Server
git add smolgura/character/manager.py
git commit -m "feat(character): create per-character memory directory with structure"
```

---

## Task 8: Character Tools (Native Tool Calling)

**Goal:** Expose `character.list`, `character.switch`, `character.info` as tools that the LLM can invoke via native tool calling.

**Files:**
- Modify: `smolgura/gura/tools.py`
- Create: `tests/character/test_tools.py`

- [ ] **Step 1: Define tool schemas and handlers**

Add to `GuraTools` (or a new `CharacterTools` class registered in the `character` namespace):

```python
# Tool schemas (JSON schema format for LLM tool calling)
CHARACTER_LIST_SCHEMA = {
    "name": "character.list",
    "description": "List all installed AI characters.",
    "parameters": {"type": "object", "properties": {}, "required": []},
}

CHARACTER_SWITCH_SCHEMA = {
    "name": "character.switch",
    "description": "Switch to a different AI character by ID. Changes personality, voice, and 3D avatar.",
    "parameters": {
        "type": "object",
        "properties": {
            "character_id": {"type": "string", "description": "ID of the character to switch to."},
        },
        "required": ["character_id"],
    },
}

CHARACTER_INFO_SCHEMA = {
    "name": "character.info",
    "description": "Get detailed information about a specific character.",
    "parameters": {
        "type": "object",
        "properties": {
            "character_id": {"type": "string", "description": "ID of the character. Omit for current character."},
        },
        "required": [],
    },
}
```

Tool handlers:

```python
async def _handle_character_list(self, **kwargs) -> dict:
    characters = self._character_manager.list()
    active_id = self._character_manager.active_id
    return {
        "characters": [
            {**c.to_list_dict(), "is_active": c.id == active_id}
            for c in characters
        ]
    }

async def _handle_character_switch(self, **kwargs) -> dict:
    character_id = kwargs["character_id"]
    char = self._character_manager.switch(character_id)
    if char is None:
        return {"success": False, "error": f"Character not found: {character_id}"}

    # Trigger async notifications (TTS, Desktop, prompt rebuild)
    if self._on_character_switched:
        await self._on_character_switched(char)

    return {"success": True, "name": char.manifest.name, "id": char.id}

async def _handle_character_info(self, **kwargs) -> dict:
    character_id = kwargs.get("character_id")
    if character_id:
        char = self._character_manager.get_character(character_id)
    else:
        char = self._character_manager.get_current()

    if char is None:
        return {"error": "No character found"}

    return char.to_detail_dict()
```

- [ ] **Step 2: Register tools in GuraTools**

In the tool registration section of `GuraTools.__init__` or wherever tools are collected, add the character tools to the `character` namespace. Gate them behind a check: only register if `character_manager is not None`.

- [ ] **Step 3: Create `tests/character/test_tools.py`**

```python
"""Tests for character tool handlers."""

import json
import zipfile
from pathlib import Path

import pytest

from smolgura.character.manager import CharacterManager


def _make_doll(tmp_path, name="test.doll"):
    doll_path = tmp_path / name
    manifest = {"formatVersion": 1, "name": "Luna", "version": "1.0", "author": "test", "avatarType": "3d"}
    with zipfile.ZipFile(doll_path, "w") as zf:
        zf.writestr("manifest.json", json.dumps(manifest))
        zf.writestr("model.glb", b"\x00" * 10)
        zf.writestr("personality.json", json.dumps({"backstory": "test"}))
    return doll_path


class TestCharacterTools:
    """Test the tool handler logic directly via CharacterManager."""

    def test_list_empty(self, tmp_path):
        mgr = CharacterManager(data_dir=tmp_path / "data")
        assert mgr.list() == []

    def test_list_with_characters(self, tmp_path):
        mgr = CharacterManager(data_dir=tmp_path / "data")
        mgr.load(_make_doll(tmp_path))
        chars = mgr.list()
        assert len(chars) == 1
        assert chars[0].manifest.name == "Luna"

    def test_switch_updates_active(self, tmp_path):
        mgr = CharacterManager(data_dir=tmp_path / "data")
        char = mgr.load(_make_doll(tmp_path))
        mgr.switch(char.id)
        current = mgr.get_current()
        assert current is not None
        assert current.id == char.id

    def test_info_current(self, tmp_path):
        mgr = CharacterManager(data_dir=tmp_path / "data")
        char = mgr.load(_make_doll(tmp_path))
        mgr.switch(char.id)
        info = mgr.get_current().to_detail_dict()
        assert info["manifest"]["name"] == "Luna"
```

- [ ] **Step 4: Run tests, commit**

```bash
cd ~/Projects/DollOS-Server
python -m pytest tests/character/test_tools.py -v
git add smolgura/gura/tools.py tests/character/test_tools.py
git commit -m "feat(character): expose character.list/switch/info as LLM tools"
```

---

## Task 9: Wire CharacterManager into GuraOS Boot Sequence

**Goal:** Create `CharacterManager` during GuraOS init, wire it into `GuraCore`, `DesktopDriver`, and `GuraTools`. Handle the async switch coordination (prompt rebuild + TTS notify + Desktop notify).

**Files:**
- Modify: `smolgura/guraos.py`
- Modify: `smolgura/config.py`

- [ ] **Step 1: Add `CharacterConfig` to `config.py`**

```python
class CharacterConfig(BaseModel):
    """Character system configuration."""

    data_dir: str = Field("/data/dollos", description="Root data directory for DollOS")
```

Add to `SystemConfig`:

```python
class SystemConfig(BaseModel):
    # ... existing fields ...
    character: CharacterConfig = Field(default_factory=CharacterConfig)
```

- [ ] **Step 2: Create CharacterManager in `GuraOS.__init__`**

In `_init_real_mode`:

```python
from smolgura.character.manager import CharacterManager

data_dir = Path(config.system.character.data_dir)
self._character_manager = CharacterManager(data_dir=data_dir)
```

In `_init_fake_mode`, set `self._character_manager = None`.

- [ ] **Step 3: Wire CharacterManager into GuraCore**

Pass it when constructing `GuraCore`:

```python
self._core = GuraCore(
    # ... existing args ...
    character_manager=self._character_manager,
)
```

- [ ] **Step 4: Wire into DesktopDriver**

In `_start_desktop`:

```python
self._desktop = DesktopDriver(
    # ... existing args ...
    character_manager=self._character_manager,
)
```

- [ ] **Step 5: Create the async switch coordinator**

Add to `GuraOS`:

```python
async def _handle_character_switch(self, character: InstalledCharacter) -> None:
    """Coordinate all side effects of a character switch.

    Called from character.switch tool handler.
    """
    # 1. Rebuild system prompt (GuraCore reads from CharacterManager.get_current())
    self._core._set_system_prompt()

    # 2. Notify TTS kmod via NATS
    if self._real_mode and self._nats_client is not None:
        await self._character_manager.notify_tts_switch(character, self._nats_client.client)

    # 3. Notify Desktop Web UI via WebSocket
    if self._desktop is not None:
        await self._desktop.broadcast_character_changed(character)

    log.info("character switch coordinated", id=character.id, name=character.manifest.name)
```

Wire this callback into `GuraTools` so `character.switch` tool can call it.

- [ ] **Step 6: Restore active character on boot**

In `_boot()`, after creating CharacterManager and before GuraCore starts, if there is an active character, set its personality into the system prompt:

```python
# After step 8 (GuraCore start), apply active character if any
if self._character_manager is not None:
    current = self._character_manager.get_current()
    if current is not None:
        self._core._set_system_prompt()
        log.info("active character restored", id=current.id, name=current.manifest.name)
```

- [ ] **Step 7: Commit**

```bash
cd ~/Projects/DollOS-Server
git add smolgura/guraos.py smolgura/config.py
git commit -m "feat(character): wire CharacterManager into GuraOS boot sequence"
```

---

## Task 10: Integration Tests

**Goal:** End-to-end tests verifying the full character lifecycle: load -> switch -> prompt changes -> list -> export -> delete.

**Files:**
- Create: `tests/character/test_integration.py`

- [ ] **Step 1: Create integration test**

```python
"""End-to-end character system integration tests."""

import json
import zipfile
from pathlib import Path

import pytest

from smolgura.character.manager import CharacterManager
from smolgura.character.models import PersonalityConfig
from smolgura.gura.prompts import render_system_prompt


def _make_doll(tmp_path, name, backstory, directive=""):
    doll_path = tmp_path / f"{name}.doll"
    manifest = {"formatVersion": 1, "name": name, "version": "1.0", "author": "test", "avatarType": "3d"}
    personality = {"backstory": backstory, "responseDirective": directive, "dynamism": 0.7, "address": "master", "languagePreference": "en"}
    voice = {"speed": 1.0, "pitch": 1.0, "ttsModel": "default", "language": "en", "provider": "local", "referenceAudio": "ref.wav"}
    with zipfile.ZipFile(doll_path, "w") as zf:
        zf.writestr("manifest.json", json.dumps(manifest))
        zf.writestr("personality.json", json.dumps(personality))
        zf.writestr("voice.json", json.dumps(voice))
        zf.writestr("model.glb", b"\x00" * 10)
        zf.writestr("ref.wav", b"\x00" * 10)
    return doll_path


class TestCharacterLifecycle:
    def test_full_lifecycle(self, tmp_path):
        data_dir = tmp_path / "dollos"
        mgr = CharacterManager(data_dir=data_dir)

        # 1. Load two characters
        luna = mgr.load(_make_doll(tmp_path, "Luna", "Luna is cheerful and warm."))
        gura = mgr.load(_make_doll(tmp_path, "Gura", "Gura is chill and blunt.", "Be casual"))
        assert luna is not None
        assert gura is not None

        # 2. List
        chars = mgr.list()
        assert len(chars) == 2

        # 3. Switch to Luna
        mgr.switch(luna.id)
        assert mgr.active_id == luna.id

        # 4. Verify prompt uses Luna's personality
        current = mgr.get_current()
        char_dict = {
            "name": current.manifest.name,
            "backstory": current.personality.backstory,
            "response_directive": current.personality.response_directive,
            "address": current.personality.address,
            "language_preference": current.personality.language_preference,
        }
        prompt = render_system_prompt(current_time="2026-03-25 12:00", character=char_dict)
        assert "[Luna]" in prompt
        assert "cheerful and warm" in prompt

        # 5. Switch to Gura custom character
        mgr.switch(gura.id)
        current = mgr.get_current()
        assert current.manifest.name == "Gura"
        assert current.personality.response_directive == "Be casual"

        # 6. Export
        export_path = tmp_path / "gura_export.doll"
        assert mgr.export(gura.id, export_path)
        with zipfile.ZipFile(export_path) as zf:
            assert "manifest.json" in zf.namelist()
            assert ".installed_meta.json" not in zf.namelist()

        # 7. Delete Luna
        assert mgr.delete(luna.id)
        assert len(mgr.list()) == 1

        # 8. Memory directories
        assert (data_dir / "memory" / gura.id).is_dir()
        assert (data_dir / "memory" / gura.id / "MEMORY.md").exists()

    def test_no_character_falls_back_to_default(self, tmp_path):
        """When no character is active, system prompt uses default Gura identity."""
        prompt = render_system_prompt(current_time="2026-03-25 12:00", character=None)
        assert "[Gura]" in prompt
```

- [ ] **Step 2: Run all character tests**

```bash
cd ~/Projects/DollOS-Server
python -m pytest tests/character/ -v
```

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/DollOS-Server
git add tests/character/test_integration.py
git commit -m "test(character): add end-to-end character system integration tests"
```

---

## Summary of Changes

| Area | What changes |
|------|-------------|
| **New module** | `smolgura/character/` — models, validator, manager |
| **System prompt** | `core_identity.j2` parameterized with character personality |
| **Prompts** | `render_system_prompt()` accepts `character` dict |
| **GuraCore** | Accepts `CharacterManager`, rebuilds prompt on switch |
| **TTS** | Publishes `kmod.tts.voice_config` NATS message on switch |
| **Desktop** | `/api/character/asset/*` HTTP endpoint + `character_changed` WS broadcast |
| **Memory** | Per-character memory dir created on load with `MEMORY.md` seed |
| **Tools** | `character.list`, `character.switch`, `character.info` native tools |
| **Config** | `CharacterConfig` in `SystemConfig` for data dir |
| **Boot** | `CharacterManager` created in `GuraOS.__init__`, active character restored on boot |

## Dependencies

- This plan does NOT depend on memsearch (Section 1) or native tool calling (Section 2) being implemented first. It works with the current codebase.
- The `get_memory_dir()` method is a hook for the future memsearch integration — memsearch will call it to resolve which memory directory to use.
- TTS kmod subscription to `kmod.tts.voice_config` is the kmod team's responsibility. This plan only publishes.
