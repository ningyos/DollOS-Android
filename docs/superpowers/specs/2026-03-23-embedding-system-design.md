# Embedding System Design

## Overview

Upgrade DollOSAIService's embedding system from placeholder to fully functional local + cloud embedding. Cloud supports any OpenAI-compatible endpoint. Local uses ONNX Runtime for on-device inference. Vector dimensions are dynamic — switching provider triggers a full vector store rebuild from Markdown source of truth.

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
- Model `.onnx` file stored in app internal storage (downloadable/replaceable)
- Tokenizer: bundled `vocab.txt` + WordPiece implementation (no external tokenizer library)
- Model and tokenizer loaded on init, kept in memory for fast inference
- Specific model TBD — must support multilingual, priority is speed
- Model not bundled in APK (too large) — downloaded on first use or manually placed

Interface:
- `LocalEmbeddingProvider` implements `EmbeddingProvider`
- `dimension` read from model output shape at load time
- `embed()` runs tokenize → ONNX session → mean pooling → normalize
- `embedBatch()` processes sequentially (ONNX Runtime on mobile is single-threaded anyway)

## Dynamic Dimensions

**No fixed dimension constraint.** Each provider reports its own `dimension`.

Vector storage:
- ObjectBox stores `FloatArray` field without `@HnswIndex` annotation
- Search uses brute-force cosine similarity (sufficient for <50K chunks on mobile, <10ms)
- `MemoryChunk.embedding` is a plain `FloatArray`, dimension is implicit

Provider switching:
1. User calls `setEmbeddingSource("cloud")` or `setEmbeddingSource("local")`
2. New provider initialized, `dimension` read
3. If dimension changed from previous: clear all embeddings in ObjectBox, re-embed all Markdown chunks
4. Rebuild runs in background (coroutine), does not block conversation
5. During rebuild, search falls back to FTS4 only (no vector results)

## AIDL Changes

Update `IDollOSAIService.aidl`:
```
// Replace existing:
void setEmbeddingSource(String source);
String getEmbeddingSource();

// Add:
void setCloudEmbeddingConfig(String baseUrl, String apiKey, String model, int dimensions);
```

## Files Changed

```
Modified:
  memory/CloudEmbeddingProvider.kt    -- configurable baseUrl, model, dimensions
  memory/LocalEmbeddingProvider.kt    -- ONNX Runtime inference, WordPiece tokenizer
  memory/MemoryChunk.kt               -- remove @HnswIndex annotation
  memory/MemorySearchEngine.kt        -- remove HNSW search path, use brute-force only
  memory/MemoryManager.kt             -- dimension change detection, rebuild trigger
  DollOSAIServiceImpl.kt              -- new AIDL method implementation
  aidl/IDollOSAIService.aidl          -- add setCloudEmbeddingConfig

New:
  memory/WordPieceTokenizer.kt        -- tokenizer for ONNX models

Dependencies:
  app/build.gradle.kts                -- add onnxruntime-android
```

## Out of Scope

- Model download UI / management
- Multiple simultaneous embedding providers
- Approximate nearest neighbor (HNSW/FAISS) — brute-force sufficient for mobile memory scale
- Embedding model training or fine-tuning
