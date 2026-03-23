# Embedding System Design

## Overview

Upgrade DollOSAIService's embedding system from placeholder to fully functional local + cloud embedding. Cloud supports any OpenAI-compatible endpoint. Local uses ONNX Runtime for on-device inference. Vector dimensions are dynamic. Each model's embeddings are stored independently — switching between models that have been used before is instant (no rebuild).

## Cloud Embedding

**Single configurable provider** supporting any OpenAI-compatible `/v1/embeddings` endpoint.

Parameters (user-configurable):
- `baseUrl` — API endpoint (default: `https://api.openai.com/v1/embeddings`)
- `apiKey` — authentication
- `model` — model name (default: `text-embedding-3-small`)
- `dimensions` — output dimensions (default: 384, used in API request if supported)

This covers OpenAI, Voyage, Cohere, Jina, any self-hosted embedding server, and any future provider that follows the OpenAI embedding API format.

Existing `CloudEmbeddingProvider` already implements the HTTP logic. Changes: make `baseUrl`, `model`, `dimensions` configurable instead of hardcoded.

## Local Embedding (ONNX Runtime)

**On-device inference** using ONNX Runtime for Android.

Architecture:
- ONNX Runtime dependency added to Gradle
- Default model bundled in system image (system_ext partition) during flash, available out of the box
- Model can be replaced by downloading a new `.onnx` file to app internal storage (overrides bundled model)
- Tokenizer: bundled `vocab.txt` + WordPiece implementation (no external tokenizer library)
- Model and tokenizer loaded on init, kept in memory for fast inference
- Specific model TBD — must support multilingual, priority is speed

Interface:
- `LocalEmbeddingProvider` implements `EmbeddingProvider`
- `dimension` read from model output shape at load time
- `embed()` runs tokenize → ONNX session → mean pooling → normalize
- `embedBatch()` processes sequentially (ONNX Runtime on mobile is single-threaded anyway)

## Dynamic Dimensions + Per-Model Vector Store

**No fixed dimension constraint.** Each provider reports its own `dimension`.

### Per-model embedding storage

`MemoryChunk` gains a `modelId` field (e.g., `"cloud:text-embedding-3-small"`, `"local:all-MiniLM-L6-v2"`). Each model's embeddings are stored independently:

- Search only queries chunks where `modelId == currentModelId`
- Switching to a previously used model: chunks already exist → instant switch, no rebuild
- Switching to a never-used model: no chunks exist → prompt user to rebuild

Vector storage:
- ObjectBox stores `FloatArray` field without `@HnswIndex` annotation
- Search uses brute-force cosine similarity (sufficient for <50K chunks on mobile, <10ms)
- `MemoryChunk.embedding` is a plain `FloatArray`, dimension is implicit from modelId

### Provider switching flow

1. User calls `setEmbeddingSource("cloud")` or `setEmbeddingSource("local")`
2. New provider initialized, `modelId` computed
3. Check: do chunks with this `modelId` exist?
   - **Yes** → switch immediately, no rebuild needed
   - **No** → notify user: "Embedding model changed. Vector store needs to be rebuilt for [model name]. Rebuild now?"
4. User confirms → rebuild starts in background
5. Rebuild progress shown as persistent Android notification: "Rebuilding vector store: 45/120 chunks"
6. Rebuild complete → notification: "Vector store rebuild complete"
7. During rebuild, search falls back to FTS4 only (no vector results)

### Rebuild behavior

- Does NOT delete other models' embeddings (non-destructive)
- Only creates new chunks for the current modelId
- Rebuild can be cancelled (partial results kept, resume later)
- If Markdown files haven't changed since last build for this model, SHA-256 dedup skips unchanged chunks

## Retrieval Modes

Two search modes:

| Mode | Vector | FTS4 | When |
|------|--------|------|------|
| **Hybrid** (default) | Yes | Yes | Vector store exists for current model |
| **FTS4 only** | No | Yes | Always available |

- FTS4 is always functional — it indexes Markdown text directly, no embedding needed
- Hybrid auto-degrades to FTS4 only when: vector store doesn't exist for current model, or rebuild is in progress
- User/AI can explicitly request FTS4 only via search mode parameter (useful for exact keyword lookup)
- AIDL: `setRetrievalMode(String mode)` — `"hybrid"` or `"fts4"`

## AIDL Changes

Update `IDollOSAIService.aidl`:
```
// Existing (keep):
void setEmbeddingSource(String source);
String getEmbeddingSource();

// Add:
void setCloudEmbeddingConfig(String baseUrl, String apiKey, String model, int dimensions);
void rebuildVectorStore();  // user-triggered rebuild
void setRetrievalMode(String mode);  // "hybrid" or "fts4"
String getRetrievalMode();
```

## Files Changed

```
Modified:
  memory/CloudEmbeddingProvider.kt    -- configurable baseUrl, model, dimensions
  memory/LocalEmbeddingProvider.kt    -- ONNX Runtime inference, WordPiece tokenizer
  memory/MemoryChunk.kt               -- remove @HnswIndex, add modelId field
  memory/MemorySearchEngine.kt        -- remove HNSW search path, filter by modelId, brute-force only
  memory/MemoryManager.kt             -- modelId tracking, rebuild trigger with notification, switch detection
  DollOSAIServiceImpl.kt              -- new AIDL method implementations
  aidl/IDollOSAIService.aidl          -- add setCloudEmbeddingConfig, rebuildVectorStore

New:
  memory/WordPieceTokenizer.kt        -- tokenizer for ONNX models

Dependencies:
  app/build.gradle.kts                -- add onnxruntime-android

System image:
  prebuilt/models/                    -- default ONNX embedding model (bundled at flash time)
```

## Out of Scope

- Model download UI / management (manual file placement for now)
- Multiple simultaneous embedding providers
- Approximate nearest neighbor (HNSW/FAISS) — brute-force sufficient for mobile memory scale
- Embedding model training or fine-tuning
- Auto-rebuild on model switch (always ask user first)
