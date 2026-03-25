# DollOS-Server Brand Rename (dev branch) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename all smolGura/smolgura references to DollOS-Server/dollos_server on the dev branch.

**Architecture:** Mechanical find-and-replace across Python package, Docker configs, CLI entry points, and documentation. No logic changes.

**Tech Stack:** Python, Docker, uv

---

## Rename Mapping

| Old | New | Context |
|-----|-----|---------|
| `smolgura/` (directory) | `dollos_server/` | Python package directory |
| `smolgura` (import) | `dollos_server` | All Python imports |
| `smolgura` (pyproject name) | `dollos-server` | pyproject.toml `[project]` name |
| `smolgura` (CLI entry point) | `dollos-server` | `[project.scripts]` |
| `smolgura.kmods` (entry point group) | `dollos_server.kmods` | All workspace `pyproject.toml` |
| `SmolGura` (prose) | `DollOS-Server` | README, docstrings, comments |
| `smolGura` (prose) | `DollOS-Server` | README, docstrings, comments |
| `SMOLGURA_CONFIG` (env var) | `DOLLOS_SERVER_CONFIG` | Dockerfile |
| `smolgura` (Docker network) | `dollos-server` | docker-compose files |
| `smolgura-` (container names) | `dollos-` | docker-compose container_name |
| `smolgura-` (volume names) | `dollos-` | docker-compose volumes |
| `SmolGura - ` (comment headers) | `DollOS-Server - ` | docker-compose comments |
| `.smolgura_history` | `.dollos_server_history` | smolgura/repl.py |
| `~/.smolgura/` | `~/.dollos_server/` | crash/diary paths |

## Exclusions

- `docs/spec.md` content (will be rewritten separately)
- `docs/plans/` and `docs/designs/` (historical plans, not worth updating)
- Git remotes (stay as-is)
- `uv.lock` (regenerated, not manually edited)
- `.claude/` memory files (not part of the codebase)
- GitHub org URLs in `[tool.uv.sources]` (e.g. `github.com/smolGura/fish-tts.git`) -- these point to real GitHub repos that may or may not be renamed; leave as-is

---

## Task 1: Rename Python package directory

- [ ] `git mv smolgura dollos_server`

```bash
cd ~/Projects/DollOS-Server
git mv smolgura dollos_server
git add -A
git commit -m "rename: smolgura/ -> dollos_server/ (directory)"
```

**Verify:** `ls dollos_server/cli.py` succeeds, `ls smolgura/` fails.

---

## Task 2: Update pyproject.toml (root)

- [ ] Change `name = "smolgura"` to `name = "dollos-server"`
- [ ] Change `description = "SmolGura"` to `description = "DollOS-Server"`
- [ ] Change `smolgura = "smolgura.cli:cli"` to `dollos-server = "dollos_server.cli:cli"`
- [ ] Change `"smolgura[audio,vision,pc-control,cloud]"` to `"dollos-server[audio,vision,pc-control,cloud]"`
- [ ] Change `"smolgura/shell/errors.py"` ruff per-file-ignores path to `"dollos_server/shell/errors.py"`

File: `~/Projects/DollOS-Server/pyproject.toml`

```diff
-name = "smolgura"
+name = "dollos-server"

-description = "SmolGura"
+description = "DollOS-Server"

-smolgura = "smolgura.cli:cli"
+dollos-server = "dollos_server.cli:cli"

-    "smolgura[audio,vision,pc-control,cloud]",
+    "dollos-server[audio,vision,pc-control,cloud]",

-"smolgura/shell/errors.py" = ["N818"]
+"dollos_server/shell/errors.py" = ["N818"]
```

```bash
git add pyproject.toml
git commit -m "rename: update root pyproject.toml names and entry points"
```

---

## Task 3: Update all Python imports in dollos_server/

- [ ] Replace all `from smolgura.` with `from dollos_server.` in `dollos_server/**/*.py`
- [ ] Replace all `import smolgura` with `import dollos_server` in `dollos_server/**/*.py`
- [ ] Replace string literal `"smolgura"` in `dollos_server/logging.py` (module name prefix strip)
- [ ] Replace `"smolgura.kmods"` entry point group in `dollos_server/guraos.py` with `"dollos_server.kmods"`

Commands:

```bash
cd ~/Projects/DollOS-Server

# Bulk replace imports
find dollos_server -name "*.py" -exec sed -i 's/from smolgura\./from dollos_server./g' {} +
find dollos_server -name "*.py" -exec sed -i 's/import smolgura/import dollos_server/g' {} +

# Fix logging.py module prefix strip
sed -i 's/parts\[0\] == "smolgura"/parts[0] == "dollos_server"/' dollos_server/logging.py

# Fix entry point group name in guraos.py
sed -i 's/"smolgura\.kmods"/"dollos_server.kmods"/g' dollos_server/guraos.py

git add dollos_server/
git commit -m "rename: update all Python imports smolgura -> dollos_server"
```

**Verify:** `grep -r "from smolgura\." dollos_server/` returns nothing.

---

## Task 4: Update docstrings and string literals in dollos_server/

- [ ] `dollos_server/cli.py`: `"""SmolGura CLI."""` -> `"""DollOS-Server CLI."""`
- [ ] `dollos_server/cli.py`: `"""SmolGura - Cognitive Autonomous AI."""` -> `"""DollOS-Server - Cognitive Autonomous AI."""`
- [ ] `dollos_server/config.py` line 1: `"""Configuration settings for SmolGura."""` -> `"""Configuration settings for DollOS-Server."""`
- [ ] `dollos_server/config.py` line 221: `"""Complete SmolGura configuration."""` -> `"""Complete DollOS-Server configuration."""`
- [ ] `dollos_server/__main__.py` line 1: `"""Allow running smolgura as: python -m smolgura"""` -> `"""Allow running dollos_server as: python -m dollos_server"""`
- [ ] `dollos_server/repl.py`: `.smolgura_history` -> `.dollos_server_history`
- [ ] `dollos_server/repl.py`: `smolgura start` -> `dollos-server start` (2 occurrences)

```bash
cd ~/Projects/DollOS-Server

sed -i 's/"""SmolGura CLI\."""/"""DollOS-Server CLI."""/' dollos_server/cli.py
sed -i 's/SmolGura - Cognitive Autonomous AI/DollOS-Server - Cognitive Autonomous AI/' dollos_server/cli.py
sed -i 's/Configuration settings for SmolGura/Configuration settings for DollOS-Server/' dollos_server/config.py
sed -i 's/Complete SmolGura configuration/Complete DollOS-Server configuration/' dollos_server/config.py
sed -i 's/Allow running smolgura as: python -m smolgura/Allow running dollos_server as: python -m dollos_server/' dollos_server/__main__.py
sed -i 's/.smolgura_history/.dollos_server_history/' dollos_server/repl.py
sed -i 's/smolgura start/dollos-server start/g' dollos_server/repl.py

git add dollos_server/
git commit -m "rename: update docstrings and string literals in dollos_server/"
```

---

## Task 5: Update ~/.smolgura/ paths

- [ ] `dollos_server/guraos.py`: `~/.smolgura/crash/` -> `~/.dollos_server/crash/`
- [ ] `dollos_server/kernel/maintenance.py`: check for `~/.smolgura` paths and update

```bash
cd ~/Projects/DollOS-Server
grep -rn "\.smolgura" dollos_server/ | grep -v __pycache__
# Update each match
sed -i 's/~\/.smolgura/~\/.dollos_server/g' dollos_server/guraos.py
sed -i 's/\.smolgura/\.dollos_server/g' dollos_server/kernel/maintenance.py

git add dollos_server/
git commit -m "rename: ~/.smolgura/ -> ~/.dollos_server/ paths"
```

---

## Task 6: Update all Python imports in tests/

- [ ] Replace all `from smolgura.` with `from dollos_server.` in `tests/**/*.py`
- [ ] Replace all `import smolgura` with `import dollos_server` in `tests/**/*.py`
- [ ] Replace all `patch("smolgura.` with `patch("dollos_server.` in `tests/**/*.py`
- [ ] Replace `"SmolGura"` string assertion in `tests/test_cli.py` line 117

```bash
cd ~/Projects/DollOS-Server

find tests -name "*.py" -exec sed -i 's/from smolgura\./from dollos_server./g' {} +
find tests -name "*.py" -exec sed -i 's/import smolgura/import dollos_server/g' {} +
find tests -name "*.py" -exec sed -i 's/patch("smolgura\./patch("dollos_server./g' {} +

# Fix test_cli.py assertion
sed -i 's/assert "SmolGura" in result.output/assert "DollOS-Server" in result.output/' tests/test_cli.py
# Fix test_cli.py docstring
sed -i 's/"""Tests for SmolGura CLI\."""/"""Tests for DollOS-Server CLI."""/' tests/test_cli.py

git add tests/
git commit -m "rename: update all imports and patches in tests/"
```

**Verify:** `grep -r "smolgura" tests/ --include="*.py"` returns nothing.

---

## Task 7: Update workspace packages (imports)

- [ ] Replace all `from smolgura.` with `from dollos_server.` in `packages/**/src/**/*.py`
- [ ] Replace all `import smolgura` with `import dollos_server` in `packages/**/src/**/*.py`
- [ ] Update docstrings: `smolGura` -> `DollOS-Server` in package source files

```bash
cd ~/Projects/DollOS-Server

find packages -name "*.py" -exec sed -i 's/from smolgura\./from dollos_server./g' {} +
find packages -name "*.py" -exec sed -i 's/import smolgura/import dollos_server/g' {} +

# Update docstrings in packages
find packages -name "*.py" -exec sed -i 's/for smolGura/for DollOS-Server/g' {} +
find packages -name "*.py" -exec sed -i 's/Discord bot for smolGura/Discord bot for DollOS-Server/g' {} +
find packages -name "*.py" -exec sed -i 's/Discord interface for smolGura/Discord interface for DollOS-Server/g' {} +
find packages -name "*.py" -exec sed -i 's/Phone driver for smolGura/Phone driver for DollOS-Server/g' {} +

git add packages/
git commit -m "rename: update Python imports in workspace packages"
```

---

## Task 8: Update workspace packages (pyproject.toml)

All 11 workspace package `pyproject.toml` files need updates:

- [ ] `packages/driver-discord/pyproject.toml`: description, dependency `"smolgura"` -> `"dollos-server"`, `[tool.uv.sources]` workspace ref
- [ ] `packages/driver-pc/pyproject.toml`: same pattern
- [ ] `packages/driver-phone/pyproject.toml`: same pattern
- [ ] `packages/kmod-audio-speaker/pyproject.toml`: same + entry point group `"smolgura.kmods"` -> `"dollos_server.kmods"`
- [ ] `packages/kmod-desktop/pyproject.toml`: same + entry point group
- [ ] `packages/kmod-fish-speech/pyproject.toml`: same + entry point group
- [ ] `packages/kmod-fun-asr/pyproject.toml`: same + entry point group
- [ ] `packages/kmod-grok/pyproject.toml`: same + entry point group
- [ ] `packages/kmod-luxtts-onnx/pyproject.toml`: same + entry point group
- [ ] `packages/kmod-qwen3-vl/pyproject.toml`: same + entry point group
- [ ] `packages/kmod-sherpa-asr/pyproject.toml`: same + entry point group

For each file, apply these replacements:

```bash
cd ~/Projects/DollOS-Server

find packages -name "pyproject.toml" -exec sed -i \
  -e 's/for smolGura/for DollOS-Server/g' \
  -e 's/"smolgura\[/"dollos-server[/g' \
  -e 's/"smolgura"/"dollos-server"/g' \
  -e 's/\[project\.entry-points\."smolgura\.kmods"\]/[project.entry-points."dollos_server.kmods"]/g' \
  -e 's/^smolgura = { workspace = true }$/dollos-server = { workspace = true }/' \
  {} +

git add packages/
git commit -m "rename: update workspace package pyproject.toml files"
```

**Verify:** `grep -r "smolgura" packages/*/pyproject.toml` returns nothing (except possibly the `smolGura/luxtts-onnx.git` GitHub URL in kmod-luxtts-onnx, which we leave as-is).

---

## Task 9: Update Dockerfiles

- [ ] `Dockerfile` (root): `COPY smolgura/ smolgura/` -> `COPY dollos_server/ dollos_server/`
- [ ] `Dockerfile` (root): `ENV SMOLGURA_CONFIG=...` -> `ENV DOLLOS_SERVER_CONFIG=...`
- [ ] `Dockerfile` (root): `ENTRYPOINT ["uv", "run", "smolgura"]` -> `ENTRYPOINT ["uv", "run", "dollos-server"]`
- [ ] `packages/kmod-fish-speech/Dockerfile`: `COPY smolgura/ smolgura/` -> `COPY dollos_server/ dollos_server/`
- [ ] `packages/kmod-fun-asr/Dockerfile`: `COPY smolgura/ smolgura/` -> `COPY dollos_server/ dollos_server/`

```bash
cd ~/Projects/DollOS-Server

sed -i 's|COPY smolgura/ smolgura/|COPY dollos_server/ dollos_server/|' Dockerfile
sed -i 's|SMOLGURA_CONFIG|DOLLOS_SERVER_CONFIG|' Dockerfile
sed -i 's|"uv", "run", "smolgura"|"uv", "run", "dollos-server"|' Dockerfile

sed -i 's|COPY smolgura/ smolgura/|COPY dollos_server/ dollos_server/|' packages/kmod-fish-speech/Dockerfile
sed -i 's|COPY smolgura/ smolgura/|COPY dollos_server/ dollos_server/|' packages/kmod-fun-asr/Dockerfile

git add Dockerfile packages/kmod-fish-speech/Dockerfile packages/kmod-fun-asr/Dockerfile
git commit -m "rename: update Dockerfiles smolgura -> dollos_server"
```

---

## Task 10: Update docker-compose.yml

- [ ] Comment header: `SmolGura` -> `DollOS-Server`
- [ ] Container names: `smolgura-nats` -> `dollos-nats`, `smolgura-milvus` -> `dollos-milvus`, `smolgura-rustfs` -> `dollos-rustfs`, `smolgura-kmod-fun-asr` -> `dollos-kmod-fun-asr`, `smolgura-kmod-fish-speech` -> `dollos-kmod-fish-speech`
- [ ] Network: `smolgura` -> `dollos-server` (name and references)
- [ ] Volume names: `smolgura-nats-data` -> `dollos-nats-data`, etc.

```bash
cd ~/Projects/DollOS-Server

sed -i \
  -e 's/# SmolGura - Infrastructure/# DollOS-Server - Infrastructure/' \
  -e 's/container_name: smolgura-/container_name: dollos-/g' \
  -e 's/name: smolgura-nats-data/name: dollos-nats-data/' \
  -e 's/name: smolgura-milvus-data/name: dollos-milvus-data/' \
  -e 's/name: smolgura-rustfs-data/name: dollos-rustfs-data/' \
  -e 's/name: smolgura-rustfs-logs/name: dollos-rustfs-logs/' \
  docker-compose.yml

# Network name (both the key and the name: value)
sed -i \
  -e 's/^  smolgura:$/  dollos-server:/' \
  -e 's/name: smolgura$/name: dollos-server/' \
  -e 's/- smolgura$/- dollos-server/' \
  docker-compose.yml

git add docker-compose.yml
git commit -m "rename: update docker-compose.yml names"
```

---

## Task 11: Update docker-compose.infer.yml

- [ ] Container names: `smolgura-chat-vllm` -> `dollos-chat-vllm`, `smolgura-embedder-vllm` -> `dollos-embedder-vllm`, `smolgura-reranker-vllm` -> `dollos-reranker-vllm`
- [ ] Commented-out container name: `smolgura-fish-speech` -> `dollos-fish-speech`
- [ ] Network: `smolgura` -> `dollos-server`
- [ ] Volume: `smolgura-vllm-cache` -> `dollos-vllm-cache`
- [ ] Commented-out GitHub URL: leave `github.com/smolgura/fish-speech.git` as-is (real repo URL)

```bash
cd ~/Projects/DollOS-Server

sed -i \
  -e 's/container_name: smolgura-/container_name: dollos-/g' \
  -e 's/name: smolgura-vllm-cache/name: dollos-vllm-cache/' \
  docker-compose.infer.yml

# Network (key, name, references)
sed -i \
  -e 's/^  smolgura:$/  dollos-server:/' \
  -e 's/name: smolgura$/name: dollos-server/' \
  -e 's/- smolgura$/- dollos-server/' \
  docker-compose.infer.yml

# Commented-out network reference
sed -i 's/#     - smolgura/#     - dollos-server/' docker-compose.infer.yml

git add docker-compose.infer.yml
git commit -m "rename: update docker-compose.infer.yml names"
```

---

## Task 12: Update config.example.yaml

- [ ] Line 2: `# smolGura - Service Configuration` -> `# DollOS-Server - Service Configuration`
- [ ] Line 101: `"smolgura.kernel.modules.pc_control.PCControlModule"` -> `"dollos_server.kernel.modules.pc_control.PCControlModule"`

```bash
cd ~/Projects/DollOS-Server

sed -i 's/# smolGura - Service Configuration/# DollOS-Server - Service Configuration/' config.example.yaml
sed -i 's/smolgura\.kernel\.modules/dollos_server.kernel.modules/' config.example.yaml

git add config.example.yaml
git commit -m "rename: update config.example.yaml branding"
```

---

## Task 13: Update README.md

- [ ] Title: `smolGura - AI companion Gura on budget` -> `DollOS-Server`
- [ ] All `smolGura` prose references -> `DollOS-Server`
- [ ] CLI examples: `smolgura start` -> `dollos-server start`, `smolgura chat` -> `dollos-server chat`, `smolgura stop` -> `dollos-server stop`
- [ ] Git clone URL: `git clone https://github.com/smolgura/smolGura.git` -> update to current repo URL
- [ ] `cd smolGura` -> `cd DollOS-Server`
- [ ] CLI table: update command column

```bash
cd ~/Projects/DollOS-Server

sed -i \
  -e 's/smolGura - AI companion Gura on budget/DollOS-Server/' \
  -e 's/## What is smolGura?/## What is DollOS-Server?/' \
  -e 's/smolGura is an emotionally/DollOS-Server is an emotionally/' \
  -e 's/smolGura runs on GuraOS/DollOS-Server runs on GuraOS/' \
  -e 's/| smolGura | Infra/| DollOS-Server | Infra/' \
  -e 's|git clone https://github.com/smolgura/smolGura.git|git clone https://github.com/smolgura/DollOS-Server.git|' \
  -e 's|cd smolGura|cd DollOS-Server|' \
  -e 's/smolgura start/dollos-server start/g' \
  -e 's/smolgura stop/dollos-server stop/g' \
  -e 's/smolgura chat/dollos-server chat/g' \
  -e 's/`smolgura /`dollos-server /g' \
  -e 's/| `smolgura/| `dollos-server/g' \
  -e 's/# Infra + smolGura/# Infra + DollOS-Server/' \
  README.md

git add README.md
git commit -m "rename: update README.md branding"
```

---

## Task 14: Update CLAUDE.md

- [ ] Line 1: `# smolGura Project` -> `# DollOS-Server Project`
- [ ] CLI commands: `smolgura start` -> `dollos-server start`, `smolgura chat` -> `dollos-server chat`
- [ ] Coverage path: `--cov=smolgura` -> `--cov=dollos_server`

```bash
cd ~/Projects/DollOS-Server

sed -i \
  -e 's/# smolGura Project/# DollOS-Server Project/' \
  -e 's/--cov=smolgura/--cov=dollos_server/' \
  -e 's/uv run smolgura/uv run dollos-server/g' \
  CLAUDE.md

git add CLAUDE.md
git commit -m "rename: update CLAUDE.md branding"
```

---

## Task 15: Update KNOWLEDGE.md and TESTING.md

- [ ] `KNOWLEDGE.md` line 1: `# SmolGura Knowledge Base` -> `# DollOS-Server Knowledge Base`
- [ ] `KNOWLEDGE.md`: `smolGura GitHub Organization` -> `DollOS-Server GitHub Organization`
- [ ] `KNOWLEDGE.md`: `~/.smolgura/` paths -> `~/.dollos_server/`
- [ ] `TESTING.md` line 1: `# SmolGura Testing Guide` -> `# DollOS-Server Testing Guide`

```bash
cd ~/Projects/DollOS-Server

sed -i \
  -e 's/# SmolGura Knowledge Base/# DollOS-Server Knowledge Base/' \
  -e 's/smolGura GitHub Organization/DollOS-Server GitHub Organization/' \
  -e 's/~\/.smolgura/~\/.dollos_server/g' \
  KNOWLEDGE.md

sed -i 's/# SmolGura Testing Guide/# DollOS-Server Testing Guide/' TESTING.md

git add KNOWLEDGE.md TESTING.md
git commit -m "rename: update KNOWLEDGE.md and TESTING.md branding"
```

---

## Task 16: Regenerate uv.lock

- [ ] Delete `uv.lock`
- [ ] Run `uv lock` to regenerate with new package name

```bash
cd ~/Projects/DollOS-Server

rm uv.lock
uv lock

git add uv.lock
git commit -m "rename: regenerate uv.lock for dollos-server package name"
```

---

## Task 17: Run tests

- [ ] Run the full test suite to verify nothing is broken

```bash
cd ~/Projects/DollOS-Server
uv run pytest tests/ -x -q --ignore=tests/test_integration --ignore=tests/test_computer_use
```

If failures occur, fix import issues and commit:

```bash
git add -A
git commit -m "rename: fix test failures from rename"
```

---

## Task 18: Final audit

- [ ] Run a final grep to ensure no stray `smolgura` references remain in source code (excluding docs/plans/, docs/designs/, .git/, uv.lock, .claude/)

```bash
cd ~/Projects/DollOS-Server
grep -rn "smolgura\|SmolGura\|smolGura\|SMOLGURA" \
  --include="*.py" --include="*.toml" --include="*.yaml" --include="*.yml" \
  --include="Dockerfile*" --include="*.md" \
  | grep -v "docs/plans/" | grep -v "docs/designs/" | grep -v "docs/spec.md" \
  | grep -v ".git/" | grep -v "uv.lock" | grep -v ".claude/" \
  | grep -v "github.com/smolGura/" | grep -v "github.com/smolgura/"
```

Expected output: empty (or only the GitHub org URLs we intentionally left).

If any remain, fix and commit:

```bash
git add -A
git commit -m "rename: fix remaining smolgura references"
```

---

## Reference: Files to modify (complete list)

### Root config files (6)
- `pyproject.toml`
- `Dockerfile`
- `docker-compose.yml`
- `docker-compose.infer.yml`
- `config.example.yaml`
- `.pre-commit-config.yaml` (no smolgura refs -- no changes needed)

### Root docs (5)
- `README.md`
- `CLAUDE.md`
- `KNOWLEDGE.md`
- `TESTING.md`
- `TODO.md` (check for references)

### Python source (dollos_server/) -- ~307 import lines across 80+ files
All `from smolgura.` imports become `from dollos_server.`.

### Tests -- ~761 import/patch lines across 100+ files
All `from smolgura.` imports and `patch("smolgura.` strings become `dollos_server`.

### Workspace packages -- 42 import lines + 11 pyproject.toml files
- `packages/driver-discord/pyproject.toml` + source files
- `packages/driver-pc/pyproject.toml` + source files
- `packages/driver-phone/pyproject.toml` + source files
- `packages/kmod-audio-speaker/pyproject.toml` + source files
- `packages/kmod-desktop/pyproject.toml` + source files
- `packages/kmod-fish-speech/pyproject.toml` + source files + `Dockerfile`
- `packages/kmod-fun-asr/pyproject.toml` + source files + `Dockerfile`
- `packages/kmod-grok/pyproject.toml` + source files
- `packages/kmod-luxtts-onnx/pyproject.toml` + source files
- `packages/kmod-qwen3-vl/pyproject.toml` + source files
- `packages/kmod-sherpa-asr/pyproject.toml` + source files
