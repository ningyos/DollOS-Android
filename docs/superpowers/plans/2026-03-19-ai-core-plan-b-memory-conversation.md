# AI Core Plan B: Memory System + Conversation Engine

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the persistent memory system and conversation engine for DollOS AI. This includes a three-tier Markdown memory architecture (inspired by OpenClaw), an ObjectBox-backed hybrid search index (vector + BM25), a serialized memory write queue with retry logic, date-segmented conversation management, proactive context compression, and memory export/import via ParcelFileDescriptor.

**Architecture:** Markdown files are the single source of truth, stored in DollOSAIService's internal storage (`/data/user/0/org.dollos.ai/files/memory/`). ObjectBox provides a local vector index for hybrid search (semantic + keyword). All memory writes flow through a single serialized queue to prevent race conditions. The conversation engine segments by date and proactively compresses context at 70-80% capacity using the foreground model asynchronously.

**Tech Stack:** Kotlin, ObjectBox (Android vector database), ONNX Runtime (local embedding -- placeholder for v1), coroutines (async compression, write queue), AIDL (memory export/import), ParcelFileDescriptor (scoped storage), JSON (pending writes, config)

**Depends on:** Plan A (DollOSAIService skeleton must exist). Plan B adds modules to the existing service.

---

## File Structure

### DollOSAIService (additions to existing from Plan A)

```
packages/apps/DollOSAIService/
  src/org/dollos/ai/
    memory/
      MemoryManager.kt         -- main memory orchestrator
      MemoryTier.kt            -- tier enum and config
      MarkdownMemoryStore.kt   -- read/write Markdown files
      MemoryWriteQueue.kt      -- serialized write queue with retry
      MemorySearchEngine.kt    -- hybrid search (vector + BM25)
      ObjectBoxIndex.kt        -- ObjectBox vector index wrapper
      EmbeddingProvider.kt     -- interface for embedding generation
      CloudEmbeddingProvider.kt -- cloud API embedding (OpenAI)
      LocalEmbeddingProvider.kt -- local ONNX embedding (placeholder)
      MemoryExporter.kt        -- export/import via ParcelFileDescriptor
    conversation/
      ConversationManager.kt   -- manages conversation state and history
      ConversationSegment.kt   -- date-based conversation segment
      ContextCompressor.kt     -- proactive context compression
      MessageStore.kt          -- persists conversation messages
```

### ObjectBox and ONNX prebuilts

```
prebuilts/dollos/
  objectbox/
    objectbox-android-4.0.3.jar     -- ObjectBox runtime JAR
    objectbox-kotlin-4.0.3.jar      -- ObjectBox Kotlin extensions
    libobjectbox-jni.so             -- native JNI lib (arm64-v8a)
    Android.bp                      -- prebuilt module definitions
  onnxruntime/
    onnxruntime-android-1.17.0.aar  -- ONNX Runtime Android AAR
    Android.bp                      -- prebuilt module definition
```

---

## Task 1: Memory Tier Definitions and MarkdownMemoryStore

**Goal:** Define the three-tier memory structure and implement basic Markdown file I/O for reading and writing memory files.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MemoryTier.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MarkdownMemoryStore.kt`

- [ ] **Step 1: Create MemoryTier.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MemoryTier.kt`:

```kotlin
package org.dollos.ai.memory

/**
 * Three-tier memory structure inspired by OpenClaw/memsearch.
 *
 * Tier 1: MEMORY.md -- always loaded into LLM context.
 * Tier 2: memory/YYYY-MM-DD.md -- daily notes, today + yesterday auto-loaded.
 * Tier 3: memory/people/, topics/, decisions/ -- deep knowledge, searched on demand.
 */
enum class MemoryTier(val description: String) {
    /** Always in context. Core facts, preferences, identity. */
    CORE("Always-loaded core memory (MEMORY.md)"),

    /** Auto-loaded for today and yesterday. Ephemeral daily notes. */
    DAILY("Date-segmented daily memory"),

    /** Deep knowledge loaded via search. People, topics, decisions. */
    DEEP("Searchable deep knowledge");
}

/**
 * Subdirectories under the deep knowledge tier.
 */
enum class DeepMemoryCategory(val dirName: String) {
    PEOPLE("people"),
    TOPICS("topics"),
    DECISIONS("decisions");
}
```

- [ ] **Step 2: Create MarkdownMemoryStore.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MarkdownMemoryStore.kt`:

```kotlin
package org.dollos.ai.memory

import android.util.Log
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Reads and writes Markdown memory files.
 * Markdown is the single source of truth -- no special parser needed, just file I/O.
 *
 * Storage root: /data/user/0/org.dollos.ai/files/memory/
 *
 * Layout:
 *   MEMORY.md                    -- Tier 1 (core)
 *   memory/2026-03-19.md         -- Tier 2 (daily)
 *   memory/people/alice.md       -- Tier 3 (deep)
 *   memory/topics/cooking.md     -- Tier 3 (deep)
 *   memory/decisions/phone.md    -- Tier 3 (deep)
 */
class MarkdownMemoryStore(private val rootDir: File) {

    companion object {
        private const val TAG = "MarkdownMemoryStore"
        private const val CORE_FILENAME = "MEMORY.md"
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    init {
        rootDir.mkdirs()
        File(rootDir, "memory").mkdirs()
        DeepMemoryCategory.entries.forEach { category ->
            File(rootDir, "memory/${category.dirName}").mkdirs()
        }
    }

    // -- Tier 1: Core Memory --

    fun readCoreMemory(): String {
        val file = File(rootDir, CORE_FILENAME)
        if (!file.exists()) {
            return ""
        }
        return try {
            file.readText()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read core memory", e)
            ""
        }
    }

    fun writeCoreMemory(content: String) {
        val file = File(rootDir, CORE_FILENAME)
        try {
            file.writeText(content)
            Log.i(TAG, "Wrote core memory (${content.length} chars)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write core memory", e)
            throw e
        }
    }

    fun appendToCoreMemory(section: String) {
        val existing = readCoreMemory()
        val updated = if (existing.isBlank()) {
            section
        } else {
            "$existing\n\n$section"
        }
        writeCoreMemory(updated)
    }

    // -- Tier 2: Daily Memory --

    fun readDailyMemory(date: LocalDate): String {
        val file = dailyFile(date)
        if (!file.exists()) {
            return ""
        }
        return try {
            file.readText()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read daily memory for $date", e)
            ""
        }
    }

    fun writeDailyMemory(date: LocalDate, content: String) {
        val file = dailyFile(date)
        try {
            file.writeText(content)
            Log.i(TAG, "Wrote daily memory for $date (${content.length} chars)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write daily memory for $date", e)
            throw e
        }
    }

    fun appendToDailyMemory(date: LocalDate, section: String) {
        val existing = readDailyMemory(date)
        val updated = if (existing.isBlank()) {
            "# ${date.format(DATE_FORMAT)}\n\n$section"
        } else {
            "$existing\n\n$section"
        }
        writeDailyMemory(date, updated)
    }

    /**
     * Load today and yesterday daily memory (auto-loaded into context).
     */
    fun loadRecentDailyMemories(): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        val todayContent = readDailyMemory(today)
        val yesterdayContent = readDailyMemory(yesterday)

        val parts = mutableListOf<String>()
        if (yesterdayContent.isNotBlank()) {
            parts.add("## Yesterday (${yesterday.format(DATE_FORMAT)})\n\n$yesterdayContent")
        }
        if (todayContent.isNotBlank()) {
            parts.add("## Today (${today.format(DATE_FORMAT)})\n\n$todayContent")
        }

        return parts.joinToString("\n\n---\n\n")
    }

    // -- Tier 3: Deep Memory --

    fun readDeepMemory(category: DeepMemoryCategory, name: String): String {
        val file = deepFile(category, name)
        if (!file.exists()) {
            return ""
        }
        return try {
            file.readText()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read deep memory ${category.dirName}/$name", e)
            ""
        }
    }

    fun writeDeepMemory(category: DeepMemoryCategory, name: String, content: String) {
        val file = deepFile(category, name)
        try {
            file.writeText(content)
            Log.i(TAG, "Wrote deep memory ${category.dirName}/$name (${content.length} chars)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write deep memory ${category.dirName}/$name", e)
            throw e
        }
    }

    fun appendToDeepMemory(category: DeepMemoryCategory, name: String, section: String) {
        val existing = readDeepMemory(category, name)
        val updated = if (existing.isBlank()) {
            "# ${name.removeSuffix(".md").replaceFirstChar { it.uppercase() }}\n\n$section"
        } else {
            "$existing\n\n$section"
        }
        writeDeepMemory(category, name, updated)
    }

    // -- Listing and search support --

    /**
     * List all memory files across all tiers.
     * Returns pairs of (relative path, File).
     */
    fun listAllMemoryFiles(): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()

        // Tier 1
        val coreFile = File(rootDir, CORE_FILENAME)
        if (coreFile.exists()) {
            result.add(CORE_FILENAME to coreFile)
        }

        // Tier 2 -- daily files
        val memoryDir = File(rootDir, "memory")
        memoryDir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.forEach { file ->
            result.add("memory/${file.name}" to file)
        }

        // Tier 3 -- deep knowledge
        DeepMemoryCategory.entries.forEach { category ->
            val catDir = File(rootDir, "memory/${category.dirName}")
            catDir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.forEach { file ->
                result.add("memory/${category.dirName}/${file.name}" to file)
            }
        }

        return result
    }

    /**
     * Delete a memory file by its relative path.
     */
    fun deleteMemoryFile(relativePath: String): Boolean {
        val file = File(rootDir, relativePath)
        if (!file.exists()) return false
        return file.delete()
    }

    // -- Private helpers --

    private fun dailyFile(date: LocalDate): File {
        return File(rootDir, "memory/${date.format(DATE_FORMAT)}.md")
    }

    private fun deepFile(category: DeepMemoryCategory, name: String): File {
        val safeName = if (name.endsWith(".md")) name else "$name.md"
        return File(rootDir, "memory/${category.dirName}/$safeName")
    }
}
```

- [ ] **Step 3: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/memory/MemoryTier.kt src/org/dollos/ai/memory/MarkdownMemoryStore.kt
git commit -m "feat: add three-tier memory definitions and Markdown file store"
```

---

## Task 2: Memory Write Queue with Retry and Pending Writes

**Goal:** Implement a single serialized write queue that all memory writes flow through, with retry logic and pending_writes.json persistence for failure recovery.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MemoryWriteQueue.kt`

- [ ] **Step 1: Create MemoryWriteQueue.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MemoryWriteQueue.kt`:

```kotlin
package org.dollos.ai.memory

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/**
 * Single serialized write queue for all memory operations.
 * Prevents race conditions by processing writes sequentially.
 *
 * Write failure handling:
 *   1. Retry up to 3 times
 *   2. On final failure, persist to pending_writes.json
 *   3. On next startup, replay pending writes before normal operation
 */
class MemoryWriteQueue(
    private val store: MarkdownMemoryStore,
    private val rootDir: File,
    private val onWriteComplete: ((MemoryWriteOp) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MemoryWriteQueue"
        private const val MAX_RETRIES = 3
        private const val PENDING_WRITES_FILE = "pending_writes.json"
    }

    /**
     * Represents a single memory write operation.
     */
    data class MemoryWriteOp(
        val id: String,
        val type: WriteType,
        val tier: MemoryTier,
        val target: String,         // filename or category/name
        val content: String,
        val append: Boolean,        // true = append, false = overwrite
        val timestamp: Long = Instant.now().toEpochMilli(),
        val retryCount: Int = 0
    ) {
        enum class WriteType {
            CORE,           // Write to MEMORY.md
            DAILY,          // Write to memory/YYYY-MM-DD.md
            DEEP            // Write to memory/{category}/{name}.md
        }

        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("id", id)
                put("type", type.name)
                put("tier", tier.name)
                put("target", target)
                put("content", content)
                put("append", append)
                put("timestamp", timestamp)
                put("retryCount", retryCount)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): MemoryWriteOp {
                return MemoryWriteOp(
                    id = json.getString("id"),
                    type = WriteType.valueOf(json.getString("type")),
                    tier = MemoryTier.valueOf(json.getString("tier")),
                    target = json.getString("target"),
                    content = json.getString("content"),
                    append = json.getBoolean("append"),
                    timestamp = json.getLong("timestamp"),
                    retryCount = json.optInt("retryCount", 0)
                )
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeChannel = Channel<MemoryWriteOp>(capacity = Channel.UNLIMITED)
    private var running = false

    /**
     * Start processing the write queue.
     * Call this on service startup after replaying pending writes.
     */
    fun start() {
        if (running) return
        running = true
        scope.launch {
            for (op in writeChannel) {
                processWrite(op)
            }
        }
        Log.i(TAG, "Write queue started")
    }

    /**
     * Stop processing the write queue.
     */
    fun stop() {
        running = false
        writeChannel.close()
        Log.i(TAG, "Write queue stopped")
    }

    /**
     * Enqueue a memory write operation.
     */
    fun enqueue(op: MemoryWriteOp) {
        val result = writeChannel.trySend(op)
        if (result.isFailure) {
            Log.e(TAG, "Failed to enqueue write op ${op.id}, persisting to pending")
            persistPendingWrite(op)
        } else {
            Log.d(TAG, "Enqueued write op ${op.id} (${op.type})")
        }
    }

    // -- Convenience enqueue methods --

    fun enqueueCoreWrite(content: String, append: Boolean = true) {
        enqueue(MemoryWriteOp(
            id = generateId(),
            type = MemoryWriteOp.WriteType.CORE,
            tier = MemoryTier.CORE,
            target = "MEMORY.md",
            content = content,
            append = append
        ))
    }

    fun enqueueDailyWrite(date: LocalDate, content: String, append: Boolean = true) {
        enqueue(MemoryWriteOp(
            id = generateId(),
            type = MemoryWriteOp.WriteType.DAILY,
            tier = MemoryTier.DAILY,
            target = date.toString(),
            content = content,
            append = append
        ))
    }

    fun enqueueDeepWrite(
        category: DeepMemoryCategory,
        name: String,
        content: String,
        append: Boolean = true
    ) {
        enqueue(MemoryWriteOp(
            id = generateId(),
            type = MemoryWriteOp.WriteType.DEEP,
            tier = MemoryTier.DEEP,
            target = "${category.dirName}/$name",
            content = content,
            append = append
        ))
    }

    // -- Pending writes replay --

    /**
     * Replay pending writes from pending_writes.json.
     * Call this on service startup BEFORE calling start().
     */
    fun replayPendingWrites() {
        val pendingFile = File(rootDir, PENDING_WRITES_FILE)
        if (!pendingFile.exists()) {
            Log.i(TAG, "No pending writes to replay")
            return
        }

        try {
            val jsonStr = pendingFile.readText()
            val arr = JSONArray(jsonStr)
            val count = arr.length()
            Log.i(TAG, "Replaying $count pending writes")

            for (i in 0 until count) {
                val op = MemoryWriteOp.fromJson(arr.getJSONObject(i))
                processWrite(op)
            }

            // Clear pending writes after successful replay
            pendingFile.delete()
            Log.i(TAG, "Pending writes replayed and cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to replay pending writes", e)
        }
    }

    // -- Internal --

    private fun processWrite(op: MemoryWriteOp) {
        var currentOp = op
        for (attempt in 1..MAX_RETRIES) {
            try {
                executeWrite(currentOp)
                Log.i(TAG, "Write op ${currentOp.id} succeeded on attempt $attempt")
                onWriteComplete?.invoke(currentOp)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Write op ${currentOp.id} failed on attempt $attempt: ${e.message}")
                currentOp = currentOp.copy(retryCount = attempt)
            }
        }

        // All retries exhausted -- persist to pending_writes.json
        Log.e(TAG, "Write op ${currentOp.id} failed after $MAX_RETRIES retries, persisting")
        persistPendingWrite(currentOp)
    }

    private fun executeWrite(op: MemoryWriteOp) {
        when (op.type) {
            MemoryWriteOp.WriteType.CORE -> {
                if (op.append) {
                    store.appendToCoreMemory(op.content)
                } else {
                    store.writeCoreMemory(op.content)
                }
            }
            MemoryWriteOp.WriteType.DAILY -> {
                val date = LocalDate.parse(op.target)
                if (op.append) {
                    store.appendToDailyMemory(date, op.content)
                } else {
                    store.writeDailyMemory(date, op.content)
                }
            }
            MemoryWriteOp.WriteType.DEEP -> {
                val parts = op.target.split("/", limit = 2)
                val category = DeepMemoryCategory.entries.first { it.dirName == parts[0] }
                val name = parts[1]
                if (op.append) {
                    store.appendToDeepMemory(category, name, op.content)
                } else {
                    store.writeDeepMemory(category, name, op.content)
                }
            }
        }
    }

    private fun persistPendingWrite(op: MemoryWriteOp) {
        val pendingFile = File(rootDir, PENDING_WRITES_FILE)
        try {
            val existing = if (pendingFile.exists()) {
                JSONArray(pendingFile.readText())
            } else {
                JSONArray()
            }
            existing.put(op.toJson())
            pendingFile.writeText(existing.toString(2))
            Log.i(TAG, "Persisted pending write ${op.id} (total: ${existing.length()})")
        } catch (e: IOException) {
            Log.e(TAG, "CRITICAL: Failed to persist pending write ${op.id}", e)
        }
    }

    private fun generateId(): String {
        return "${Instant.now().toEpochMilli()}-${(Math.random() * 10000).toInt()}"
    }
}
```

- [ ] **Step 2: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/memory/MemoryWriteQueue.kt
git commit -m "feat: add serialized memory write queue with retry and pending persistence"
```

---

## Task 3: Embedding Provider Interface and Implementations

**Goal:** Define the embedding provider interface, implement cloud embedding (OpenAI text-embedding-3-small), and a local ONNX placeholder that returns zero vectors.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/memory/EmbeddingProvider.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/memory/CloudEmbeddingProvider.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/memory/LocalEmbeddingProvider.kt`

- [ ] **Step 1: Create EmbeddingProvider.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/memory/EmbeddingProvider.kt`:

```kotlin
package org.dollos.ai.memory

/**
 * Interface for generating text embeddings.
 * Used by the memory search engine for vector similarity search.
 *
 * Two implementations:
 *   - CloudEmbeddingProvider: OpenAI text-embedding-3-small (1536 dims)
 *   - LocalEmbeddingProvider: local ONNX all-MiniLM-L6-v2 (384 dims) -- placeholder for v1
 */
interface EmbeddingProvider {

    /** Dimension of the embedding vectors produced by this provider. */
    val dimension: Int

    /** Human-readable name for logging. */
    val name: String

    /** Whether this provider requires network access. */
    val requiresNetwork: Boolean

    /**
     * Generate an embedding vector for the given text.
     * Returns a float array of size [dimension].
     * Throws on failure (network error, invalid API key, etc.).
     */
    suspend fun embed(text: String): FloatArray

    /**
     * Generate embeddings for multiple texts in a batch.
     * Default implementation calls [embed] for each text sequentially.
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }
}
```

- [ ] **Step 2: Create CloudEmbeddingProvider.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/memory/CloudEmbeddingProvider.kt`:

```kotlin
package org.dollos.ai.memory

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud embedding via OpenAI text-embedding-3-small API.
 * Dimension: 1536.
 *
 * Requires a valid OpenAI API key set via [setApiKey].
 */
class CloudEmbeddingProvider : EmbeddingProvider {

    companion object {
        private const val TAG = "CloudEmbeddingProvider"
        private const val OPENAI_EMBEDDING_URL = "https://api.openai.com/v1/embeddings"
        private const val MODEL = "text-embedding-3-small"
        private const val EMBEDDING_DIMENSION = 1536
    }

    override val dimension: Int = EMBEDDING_DIMENSION
    override val name: String = "OpenAI text-embedding-3-small"
    override val requiresNetwork: Boolean = true

    private var apiKey: String = ""

    fun setApiKey(key: String) {
        apiKey = key
    }

    override suspend fun embed(text: String): FloatArray {
        if (apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API key not set for embedding")
        }

        val requestBody = JSONObject().apply {
            put("input", text)
            put("model", MODEL)
        }

        val connection = URL(OPENAI_EMBEDDING_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                throw RuntimeException(
                    "OpenAI embedding API returned $responseCode: $errorBody"
                )
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(responseBody)
            val embeddingArray = json
                .getJSONArray("data")
                .getJSONObject(0)
                .getJSONArray("embedding")

            return jsonArrayToFloatArray(embeddingArray)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        if (apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API key not set for embedding")
        }
        if (texts.isEmpty()) return emptyList()

        val inputArray = JSONArray()
        texts.forEach { inputArray.put(it) }

        val requestBody = JSONObject().apply {
            put("input", inputArray)
            put("model", MODEL)
        }

        val connection = URL(OPENAI_EMBEDDING_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                throw RuntimeException(
                    "OpenAI embedding batch API returned $responseCode: $errorBody"
                )
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(responseBody)
            val dataArray = json.getJSONArray("data")

            val results = mutableListOf<FloatArray>()
            for (i in 0 until dataArray.length()) {
                val embeddingArray = dataArray.getJSONObject(i).getJSONArray("embedding")
                results.add(jsonArrayToFloatArray(embeddingArray))
            }

            Log.i(TAG, "Batch embedded ${texts.size} texts")
            return results
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonArrayToFloatArray(arr: JSONArray): FloatArray {
        val result = FloatArray(arr.length())
        for (i in 0 until arr.length()) {
            result[i] = arr.getDouble(i).toFloat()
        }
        return result
    }
}
```

- [ ] **Step 3: Create LocalEmbeddingProvider.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/memory/LocalEmbeddingProvider.kt`:

```kotlin
package org.dollos.ai.memory

import android.util.Log

/**
 * Local ONNX embedding provider placeholder.
 *
 * Target model: all-MiniLM-L6-v2 (~23MB, 384 dimensions).
 * For v1, this returns zero vectors. Actual ONNX Runtime integration
 * will be implemented in a future plan.
 *
 * When fully implemented, this will:
 *   1. Load all-MiniLM-L6-v2.onnx from assets
 *   2. Tokenize input text (WordPiece tokenizer)
 *   3. Run inference via ONNX Runtime Android
 *   4. Mean-pool the token embeddings
 *   5. L2-normalize the result
 */
class LocalEmbeddingProvider : EmbeddingProvider {

    companion object {
        private const val TAG = "LocalEmbeddingProvider"
        private const val EMBEDDING_DIMENSION = 384
    }

    override val dimension: Int = EMBEDDING_DIMENSION
    override val name: String = "Local ONNX all-MiniLM-L6-v2 (placeholder)"
    override val requiresNetwork: Boolean = false

    override suspend fun embed(text: String): FloatArray {
        Log.w(TAG, "Using placeholder zero vector -- local ONNX not yet implemented")
        return FloatArray(EMBEDDING_DIMENSION)
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        Log.w(TAG, "Using placeholder zero vectors -- local ONNX not yet implemented")
        return texts.map { FloatArray(EMBEDDING_DIMENSION) }
    }
}
```

- [ ] **Step 4: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/memory/EmbeddingProvider.kt
git add src/org/dollos/ai/memory/CloudEmbeddingProvider.kt
git add src/org/dollos/ai/memory/LocalEmbeddingProvider.kt
git commit -m "feat: add embedding provider interface with cloud and local implementations"
```

---

## Task 4: ObjectBox Vector Index Wrapper

**Goal:** Implement the ObjectBox vector database wrapper for storing and querying memory embeddings, with a substrate-search capability for hybrid (vector + BM25) search.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/memory/ObjectBoxIndex.kt`

- [ ] **Step 1: Create ObjectBoxIndex.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/memory/ObjectBoxIndex.kt`:

```kotlin
package org.dollos.ai.memory

import android.content.Context
import android.util.Log
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.query.QueryBuilder

/**
 * ObjectBox entity for memory chunks.
 * Each chunk is a section of a Markdown memory file with its embedding vector.
 */
@Entity
data class MemoryChunk(
    @Id var id: Long = 0,
    /** Relative path to the source Markdown file (e.g., "memory/people/alice.md"). */
    var filePath: String = "",
    /** The text content of this chunk. */
    var content: String = "",
    /** Last modified timestamp of the source file when this chunk was indexed. */
    var fileModifiedAt: Long = 0,
    /** The embedding vector for semantic search. */
    @HnswIndex(dimensions = 1536)
    var embedding: FloatArray = FloatArray(0)
) {
    // ObjectBox requires a no-arg constructor
    constructor() : this(0, "", "", 0, FloatArray(0))
}

/**
 * Wraps ObjectBox for memory vector search.
 *
 * ObjectBox is purely a search index -- Markdown files are the source of truth.
 * If the ObjectBox database is corrupted or missing, it can be fully rebuilt
 * from the Markdown files.
 *
 * Provides hybrid search: vector similarity + BM25 keyword matching,
 * with configurable weights.
 */
class ObjectBoxIndex(
    private val context: Context,
    private val embeddingProvider: EmbeddingProvider
) {
    companion object {
        private const val TAG = "ObjectBoxIndex"
        private const val DEFAULT_VECTOR_WEIGHT = 0.7f
        private const val DEFAULT_BM25_WEIGHT = 0.3f
        private const val MAX_RESULTS = 10
    }

    private var boxStore: BoxStore? = null
    private var chunkBox: Box<MemoryChunk>? = null
    private var available = false

    var vectorWeight: Float = DEFAULT_VECTOR_WEIGHT
    var bm25Weight: Float = DEFAULT_BM25_WEIGHT

    /**
     * Initialize ObjectBox. Call on service startup.
     * If initialization fails, the index is unavailable and search
     * falls back to substring matching in MemorySearchEngine.
     */
    fun init() {
        try {
            boxStore = MyObjectBox.builder()
                .androidContext(context)
                .name("dollos-memory-index")
                .build()
            chunkBox = boxStore?.boxFor(MemoryChunk::class.java)
            available = true
            Log.i(TAG, "ObjectBox initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "ObjectBox initialization failed, search will use fallback", e)
            available = false
        }
    }

    /**
     * Whether ObjectBox is available for search.
     */
    fun isAvailable(): Boolean = available

    /**
     * Index a memory file. Splits content into chunks and stores with embeddings.
     * Replaces any existing chunks for the same file path.
     */
    suspend fun indexFile(filePath: String, content: String, fileModifiedAt: Long) {
        val box = chunkBox ?: run {
            Log.w(TAG, "ObjectBox not available, skipping index for $filePath")
            return
        }

        // Remove old chunks for this file
        removeFile(filePath)

        // Split content into chunks (by section headers or paragraphs)
        val chunks = splitIntoChunks(content)
        if (chunks.isEmpty()) return

        // Generate embeddings
        val embeddings = try {
            embeddingProvider.embedBatch(chunks)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate embeddings for $filePath", e)
            // Store chunks without embeddings -- BM25 search still works
            chunks.map { FloatArray(embeddingProvider.dimension) }
        }

        // Store chunks
        val entities = chunks.mapIndexed { i, chunkText ->
            MemoryChunk(
                filePath = filePath,
                content = chunkText,
                fileModifiedAt = fileModifiedAt,
                embedding = embeddings[i]
            )
        }
        box.put(entities)
        Log.i(TAG, "Indexed $filePath: ${entities.size} chunks")
    }

    /**
     * Remove all chunks for a file path.
     */
    fun removeFile(filePath: String) {
        val box = chunkBox ?: return
        val existing = box.query()
            .equal(MemoryChunk_.filePath, filePath)
            .build()
            .find()
        if (existing.isNotEmpty()) {
            box.remove(existing)
            Log.d(TAG, "Removed ${existing.size} old chunks for $filePath")
        }
    }

    /**
     * Hybrid search: combines vector similarity and BM25 keyword matching.
     * Returns up to [maxResults] chunks sorted by combined score.
     */
    suspend fun search(query: String, maxResults: Int = MAX_RESULTS): List<SearchResult> {
        val box = chunkBox ?: return emptyList()

        // Vector search
        val vectorResults = vectorSearch(query, maxResults * 2)

        // BM25 keyword search
        val bm25Results = bm25Search(query, maxResults * 2)

        // Merge results with configurable weights
        return mergeResults(vectorResults, bm25Results, maxResults)
    }

    /**
     * Vector similarity search using ObjectBox HNSW index.
     */
    private suspend fun vectorSearch(query: String, maxResults: Int): List<ScoredChunk> {
        val box = chunkBox ?: return emptyList()

        val queryEmbedding = try {
            embeddingProvider.embed(query)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to embed query for vector search", e)
            return emptyList()
        }

        // Check if the embedding is all zeros (placeholder provider)
        val isZeroVector = queryEmbedding.all { it == 0f }
        if (isZeroVector) {
            Log.d(TAG, "Skipping vector search -- zero embedding (placeholder provider)")
            return emptyList()
        }

        return try {
            val results = box.query()
                .nearestNeighbors(MemoryChunk_.embedding, queryEmbedding, maxResults)
                .build()
                .findWithScores()

            results.map { scoredResult ->
                ScoredChunk(
                    chunk = scoredResult.get(),
                    score = 1.0f / (1.0f + scoredResult.score.toFloat()) // Convert distance to similarity
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vector search failed", e)
            emptyList()
        }
    }

    /**
     * BM25-style keyword search.
     * ObjectBox does not have built-in BM25, so we use a simple term-frequency approach:
     *   score = (number of query terms found in chunk) / (total query terms)
     */
    private fun bm25Search(query: String, maxResults: Int): List<ScoredChunk> {
        val box = chunkBox ?: return emptyList()

        val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        if (queryTerms.isEmpty()) return emptyList()

        val allChunks = box.all
        val scored = allChunks.mapNotNull { chunk ->
            val contentLower = chunk.content.lowercase()
            val matchCount = queryTerms.count { term -> contentLower.contains(term) }
            if (matchCount > 0) {
                ScoredChunk(chunk, matchCount.toFloat() / queryTerms.size.toFloat())
            } else {
                null
            }
        }.sortedByDescending { it.score }

        return scored.take(maxResults)
    }

    /**
     * Merge vector and BM25 results using configurable weights.
     * Uses reciprocal rank fusion for combining ranked lists.
     */
    private fun mergeResults(
        vectorResults: List<ScoredChunk>,
        bm25Results: List<ScoredChunk>,
        maxResults: Int
    ): List<SearchResult> {
        val scoreMap = mutableMapOf<Long, MergedScore>()

        // Add vector scores
        vectorResults.forEachIndexed { rank, scored ->
            val id = scored.chunk.id
            val existing = scoreMap.getOrPut(id) { MergedScore(scored.chunk) }
            existing.vectorScore = scored.score
            existing.vectorRank = rank
        }

        // Add BM25 scores
        bm25Results.forEachIndexed { rank, scored ->
            val id = scored.chunk.id
            val existing = scoreMap.getOrPut(id) { MergedScore(scored.chunk) }
            existing.bm25Score = scored.score
            existing.bm25Rank = rank
        }

        // Compute combined score using reciprocal rank fusion
        val k = 60 // RRF constant
        return scoreMap.values
            .map { merged ->
                val vectorRRF = if (merged.vectorRank >= 0) {
                    vectorWeight / (k + merged.vectorRank)
                } else 0f
                val bm25RRF = if (merged.bm25Rank >= 0) {
                    bm25Weight / (k + merged.bm25Rank)
                } else 0f

                SearchResult(
                    filePath = merged.chunk.filePath,
                    content = merged.chunk.content,
                    score = vectorRRF + bm25RRF,
                    vectorScore = merged.vectorScore,
                    bm25Score = merged.bm25Score
                )
            }
            .sortedByDescending { it.score }
            .take(maxResults)
    }

    /**
     * Rebuild the entire index from Markdown files.
     * Call this when the index is corrupted or on first run.
     */
    suspend fun rebuildFromFiles(store: MarkdownMemoryStore) {
        val box = chunkBox ?: run {
            Log.w(TAG, "ObjectBox not available, cannot rebuild index")
            return
        }

        Log.i(TAG, "Rebuilding index from Markdown files...")
        box.removeAll()

        val files = store.listAllMemoryFiles()
        var totalChunks = 0
        for ((relativePath, file) in files) {
            try {
                val content = file.readText()
                if (content.isNotBlank()) {
                    indexFile(relativePath, content, file.lastModified())
                    totalChunks++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to index $relativePath during rebuild", e)
            }
        }
        Log.i(TAG, "Index rebuild complete: $totalChunks files indexed")
    }

    /**
     * Check if the index is consistent with the Markdown files.
     * Returns true if all files are indexed and up to date.
     */
    fun isConsistentWith(store: MarkdownMemoryStore): Boolean {
        val box = chunkBox ?: return false

        val files = store.listAllMemoryFiles()
        for ((relativePath, file) in files) {
            val chunks = box.query()
                .equal(MemoryChunk_.filePath, relativePath)
                .build()
                .find()

            if (chunks.isEmpty()) {
                Log.d(TAG, "Index missing file: $relativePath")
                return false
            }

            // Check if file was modified after indexing
            val indexedAt = chunks.first().fileModifiedAt
            if (file.lastModified() > indexedAt) {
                Log.d(TAG, "Index stale for file: $relativePath")
                return false
            }
        }

        return true
    }

    /**
     * Close the ObjectBox store. Call on service shutdown.
     */
    fun close() {
        boxStore?.close()
        boxStore = null
        chunkBox = null
        available = false
        Log.i(TAG, "ObjectBox closed")
    }

    // -- Helpers --

    /**
     * Split Markdown content into chunks by section headers (##) or double newlines.
     * Each chunk is a meaningful unit for embedding and search.
     */
    private fun splitIntoChunks(content: String): List<String> {
        if (content.isBlank()) return emptyList()

        // Split by ## headers first
        val sections = content.split(Regex("(?=^## )", RegexOption.MULTILINE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // If no sections found, split by double newlines
        if (sections.size <= 1) {
            val paragraphs = content.split(Regex("\n\n+"))
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length > 20 }
            if (paragraphs.isNotEmpty()) return paragraphs
        }

        return sections
    }

    // -- Internal data classes --

    private data class ScoredChunk(val chunk: MemoryChunk, val score: Float)

    private data class MergedScore(
        val chunk: MemoryChunk,
        var vectorScore: Float = 0f,
        var bm25Score: Float = 0f,
        var vectorRank: Int = -1,
        var bm25Rank: Int = -1
    )
}

/**
 * A search result from hybrid search.
 */
data class SearchResult(
    val filePath: String,
    val content: String,
    val score: Float,
    val vectorScore: Float = 0f,
    val bm25Score: Float = 0f
)
```

- [ ] **Step 2: Note on ObjectBox code generation**

ObjectBox uses an annotation processor to generate `MyObjectBox` and `MemoryChunk_` classes at compile time. The `@Entity` and `@HnswIndex` annotations on `MemoryChunk` trigger this generation.

For the AOSP build system, ObjectBox's annotation processor (`objectbox-processor`) must be configured. If ObjectBox annotation processing is not available in the AOSP Soong build, a workaround is to:
1. Pre-generate the ObjectBox model files from a Gradle-based project
2. Include the generated `MyObjectBox.java` and property classes as source files
3. OR use ObjectBox without annotations (manual BoxStore setup with `ModelBuilder`)

This will be resolved during Task 8 (build integration). For now, the code references `MyObjectBox` and `MemoryChunk_` as if they are generated.

- [ ] **Step 3: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/memory/ObjectBoxIndex.kt
git commit -m "feat: add ObjectBox vector index wrapper with hybrid search"
```

---

## Task 5: Memory Search Engine (Hybrid Search with Fallback)

**Goal:** Implement the top-level search engine that uses ObjectBox for hybrid search when available, falling back to simple substring search when ObjectBox is unavailable.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MemorySearchEngine.kt`

- [ ] **Step 1: Create MemorySearchEngine.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MemorySearchEngine.kt`:

```kotlin
package org.dollos.ai.memory

import android.util.Log

/**
 * Top-level memory search engine.
 *
 * Uses ObjectBox hybrid search (vector + BM25) when available.
 * Falls back to simple substring matching across all Markdown files
 * when ObjectBox is unavailable (init failed, not yet ready, etc.).
 *
 * Markdown files are always the source of truth.
 */
class MemorySearchEngine(
    private val store: MarkdownMemoryStore,
    private val objectBoxIndex: ObjectBoxIndex?
) {
    companion object {
        private const val TAG = "MemorySearchEngine"
        private const val MAX_RESULTS = 10
        private const val MIN_QUERY_LENGTH = 2
    }

    /**
     * Search memory for content relevant to the query.
     * Returns a list of SearchResult sorted by relevance.
     */
    suspend fun search(query: String, maxResults: Int = MAX_RESULTS): List<SearchResult> {
        if (query.length < MIN_QUERY_LENGTH) {
            Log.d(TAG, "Query too short, skipping search")
            return emptyList()
        }

        // Try ObjectBox hybrid search first
        if (objectBoxIndex != null && objectBoxIndex.isAvailable()) {
            try {
                val results = objectBoxIndex.search(query, maxResults)
                Log.i(TAG, "ObjectBox search returned ${results.size} results for: $query")
                return results
            } catch (e: Exception) {
                Log.e(TAG, "ObjectBox search failed, falling back to substring search", e)
            }
        }

        // Fallback: simple substring search across all Markdown files
        return substringSearch(query, maxResults)
    }

    /**
     * Simple substring search across all Markdown memory files.
     * Scores by number of query term occurrences in each file section.
     */
    private fun substringSearch(query: String, maxResults: Int): List<SearchResult> {
        val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.length > MIN_QUERY_LENGTH }
        if (queryTerms.isEmpty()) return emptyList()

        val results = mutableListOf<SearchResult>()
        val files = store.listAllMemoryFiles()

        for ((relativePath, file) in files) {
            try {
                val content = file.readText()
                if (content.isBlank()) continue

                // Split into sections for granular results
                val sections = splitIntoSections(content)
                for (section in sections) {
                    val sectionLower = section.lowercase()
                    val matchCount = queryTerms.count { term -> sectionLower.contains(term) }
                    if (matchCount > 0) {
                        val score = matchCount.toFloat() / queryTerms.size.toFloat()
                        results.add(SearchResult(
                            filePath = relativePath,
                            content = section,
                            score = score,
                            bm25Score = score
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading $relativePath during search", e)
            }
        }

        Log.i(TAG, "Substring search returned ${results.size} results for: $query")
        return results
            .sortedByDescending { it.score }
            .take(maxResults)
    }

    /**
     * Split Markdown content into sections for search granularity.
     */
    private fun splitIntoSections(content: String): List<String> {
        val sections = content.split(Regex("(?=^## )", RegexOption.MULTILINE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (sections.size <= 1) {
            // No headers -- split by double newlines
            val paragraphs = content.split(Regex("\n\n+"))
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length > 20 }
            if (paragraphs.isNotEmpty()) return paragraphs
            // Return whole content as single section
            return listOf(content.trim())
        }

        return sections
    }
}
```

- [ ] **Step 2: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/memory/MemorySearchEngine.kt
git commit -m "feat: add memory search engine with ObjectBox hybrid and substring fallback"
```

---

## Task 6: Memory Manager (Orchestrator with Write Triggers)

**Goal:** Implement the main memory orchestrator that ties together all memory components, handles write triggers (context threshold, idle, event-based), and manages the "remember this" flow.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MemoryManager.kt`

- [ ] **Step 1: Create MemoryManager.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MemoryManager.kt`:

```kotlin
package org.dollos.ai.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/**
 * Main memory orchestrator.
 *
 * Manages three-tier memory, coordinates write triggers, handles "remember this" flow,
 * and keeps the ObjectBox index in sync with Markdown files.
 *
 * Write triggers (all feed into a single serialized write queue):
 *   1. Context threshold -- extract facts when context reaches 70-80% capacity
 *   2. Idle background -- review recent conversation after N minutes idle
 *   3. Event-based -- screen lock, app switch, "remember this"
 */
class MemoryManager(
    private val context: Context,
    private val embeddingSource: String = "cloud" // "cloud" or "local"
) {
    companion object {
        private const val TAG = "MemoryManager"
        private const val IDLE_TRIGGER_DELAY_MS = 5L * 60 * 1000 // 5 minutes
    }

    private val rootDir = File(context.filesDir, "memory")
    val store = MarkdownMemoryStore(rootDir)
    private val embeddingProvider: EmbeddingProvider = createEmbeddingProvider(embeddingSource)
    val objectBoxIndex = ObjectBoxIndex(context, embeddingProvider)
    val searchEngine = MemorySearchEngine(store, objectBoxIndex)
    val writeQueue = MemoryWriteQueue(store, rootDir) { op ->
        // After each successful write, update the ObjectBox index
        onWriteComplete(op)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var idleJob: Job? = null
    private var pendingRememberContent: String? = null

    /**
     * Callback for when the AI presents a "remember this" confirmation to the user.
     * Set this to route the confirmation through the AIDL callback.
     */
    var onMemoryConfirmRequired: ((String) -> Unit)? = null

    /**
     * Initialize the memory system. Call on service startup.
     */
    fun init() {
        Log.i(TAG, "Initializing memory system...")

        // Replay any pending writes from previous crash/failure
        writeQueue.replayPendingWrites()

        // Start the write queue
        writeQueue.start()

        // Initialize ObjectBox index
        objectBoxIndex.init()

        // Check index consistency and rebuild if needed
        if (objectBoxIndex.isAvailable()) {
            scope.launch {
                if (!objectBoxIndex.isConsistentWith(store)) {
                    Log.i(TAG, "ObjectBox index inconsistent, rebuilding...")
                    objectBoxIndex.rebuildFromFiles(store)
                }
            }
        }

        // Ensure core memory file exists
        if (store.readCoreMemory().isBlank()) {
            store.writeCoreMemory("# Memory\n\nCore facts and preferences.\n")
            Log.i(TAG, "Created initial MEMORY.md")
        }

        Log.i(TAG, "Memory system initialized")
    }

    /**
     * Shut down the memory system. Call on service shutdown.
     */
    fun shutdown() {
        idleJob?.cancel()
        writeQueue.stop()
        objectBoxIndex.close()
        Log.i(TAG, "Memory system shut down")
    }

    // -- Context loading for LLM --

    /**
     * Build the memory context to inject into the LLM system prompt.
     * Includes Tier 1 (always) + Tier 2 (today + yesterday).
     */
    fun buildMemoryContext(): String {
        val parts = mutableListOf<String>()

        // Tier 1: Core memory
        val coreMemory = store.readCoreMemory()
        if (coreMemory.isNotBlank()) {
            parts.add("# Core Memory\n\n$coreMemory")
        }

        // Tier 2: Recent daily memory
        val dailyMemory = store.loadRecentDailyMemories()
        if (dailyMemory.isNotBlank()) {
            parts.add("# Recent Notes\n\n$dailyMemory")
        }

        return parts.joinToString("\n\n---\n\n")
    }

    /**
     * Search Tier 3 memory for relevant context based on user message.
     * Returns formatted search results to include in the LLM context.
     */
    suspend fun searchRelevantMemory(query: String): String {
        val results = searchEngine.search(query, maxResults = 5)
        if (results.isEmpty()) return ""

        val formatted = results.joinToString("\n\n") { result ->
            "### ${result.filePath}\n${result.content}"
        }

        return "# Relevant Memory (from search)\n\n$formatted"
    }

    // -- Write trigger 1: Context threshold --

    /**
     * Called when context usage reaches the compression threshold (70-80%).
     * Extracts important facts from the conversation before compression.
     *
     * @param extractedFacts Facts extracted by the LLM from the conversation being compressed.
     */
    fun onContextThresholdReached(extractedFacts: List<String>) {
        if (extractedFacts.isEmpty()) return

        val today = LocalDate.now()
        val content = extractedFacts.joinToString("\n") { "- $it" }

        writeQueue.enqueueDailyWrite(
            date = today,
            content = "### From conversation (compressed)\n\n$content"
        )

        Log.i(TAG, "Context threshold triggered: ${extractedFacts.size} facts queued for write")
    }

    // -- Write trigger 2: Idle background --

    /**
     * Reset the idle timer. Call after each user message or AI response.
     */
    fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TRIGGER_DELAY_MS)
            onIdleTriggered()
        }
    }

    /**
     * Cancel the idle timer. Call when the conversation becomes active.
     */
    fun cancelIdleTimer() {
        idleJob?.cancel()
        idleJob = null
    }

    /**
     * Called when the idle timer fires.
     * The actual memory extraction is done by the LLM (background model) --
     * this method is the trigger point that the ConversationManager calls.
     */
    private fun onIdleTriggered() {
        Log.i(TAG, "Idle trigger fired after ${IDLE_TRIGGER_DELAY_MS / 1000}s")
        onIdleCallback?.invoke()
    }

    /**
     * Callback invoked when idle trigger fires.
     * Set this so ConversationManager can send recent conversation to background model
     * for memory extraction.
     */
    var onIdleCallback: (() -> Unit)? = null

    /**
     * Write memories extracted by the background model during idle processing.
     *
     * @param memories List of memory items to write.
     */
    fun writeIdleExtractedMemories(memories: List<ExtractedMemory>) {
        val today = LocalDate.now()
        for (memory in memories) {
            when (memory.tier) {
                MemoryTier.CORE -> {
                    writeQueue.enqueueCoreWrite(memory.content)
                }
                MemoryTier.DAILY -> {
                    writeQueue.enqueueDailyWrite(today, memory.content)
                }
                MemoryTier.DEEP -> {
                    val category = memory.category ?: DeepMemoryCategory.TOPICS
                    val name = memory.name ?: "uncategorized"
                    writeQueue.enqueueDeepWrite(category, name, memory.content)
                }
            }
        }
        Log.i(TAG, "Idle extraction: ${memories.size} memories queued for write")
    }

    // -- Write trigger 3: Event-based --

    /**
     * Called on screen lock or app switch.
     * Similar to idle trigger but immediate.
     */
    fun onScreenLockOrAppSwitch() {
        Log.i(TAG, "Event trigger: screen lock or app switch")
        onIdleCallback?.invoke()
    }

    /**
     * Handle "remember this" request from user.
     * The AI extracts and formats the memory, then presents to user for confirmation.
     *
     * @param formattedMemory The memory content formatted by the AI.
     */
    fun requestRememberThis(formattedMemory: String) {
        pendingRememberContent = formattedMemory
        onMemoryConfirmRequired?.invoke(formattedMemory)
        Log.i(TAG, "Remember-this: presented to user for confirmation")
    }

    /**
     * User confirms or denies the "remember this" write.
     *
     * @param approved True if user approves, false if denied.
     */
    fun confirmRememberThis(approved: Boolean) {
        val content = pendingRememberContent
        pendingRememberContent = null

        if (!approved || content == null) {
            Log.i(TAG, "Remember-this: user denied or no pending content")
            return
        }

        val today = LocalDate.now()
        writeQueue.enqueueDailyWrite(
            date = today,
            content = "### Remembered\n\n$content"
        )
        Log.i(TAG, "Remember-this: user approved, queued for write")
    }

    // -- Index sync --

    /**
     * Called after a successful memory write to update the ObjectBox index.
     */
    private fun onWriteComplete(op: MemoryWriteQueue.MemoryWriteOp) {
        if (!objectBoxIndex.isAvailable()) return

        scope.launch {
            try {
                val relativePath = when (op.type) {
                    MemoryWriteQueue.MemoryWriteOp.WriteType.CORE -> "MEMORY.md"
                    MemoryWriteQueue.MemoryWriteOp.WriteType.DAILY -> "memory/${op.target}.md"
                    MemoryWriteQueue.MemoryWriteOp.WriteType.DEEP -> "memory/${op.target}.md"
                }

                // Re-read the full file content (may have been appended to)
                val allFiles = store.listAllMemoryFiles()
                val match = allFiles.find { it.first == relativePath }
                if (match != null) {
                    val content = match.second.readText()
                    objectBoxIndex.indexFile(relativePath, content, match.second.lastModified())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update index after write op ${op.id}", e)
            }
        }
    }

    // -- Embedding source management --

    /**
     * Switch the embedding source. Triggers a full index rebuild since
     * the vector dimensions may differ between providers.
     */
    fun setEmbeddingSource(source: String) {
        Log.i(TAG, "Switching embedding source to: $source")
        // Note: In a real implementation, this would recreate the embeddingProvider
        // and rebuild the ObjectBox index. For now, the provider is set at init time.
        // Changing it requires restarting the memory system.
        Log.w(TAG, "Embedding source change requires service restart to take effect")
    }

    // -- Helpers --

    private fun createEmbeddingProvider(source: String): EmbeddingProvider {
        return when (source) {
            "cloud" -> CloudEmbeddingProvider()
            "local" -> LocalEmbeddingProvider()
            else -> {
                Log.w(TAG, "Unknown embedding source '$source', defaulting to local")
                LocalEmbeddingProvider()
            }
        }
    }
}

/**
 * Represents a memory item extracted by the LLM during idle processing.
 */
data class ExtractedMemory(
    val tier: MemoryTier,
    val content: String,
    val category: DeepMemoryCategory? = null,  // Only for DEEP tier
    val name: String? = null                    // Only for DEEP tier (filename without .md)
)
```

- [ ] **Step 2: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/memory/MemoryManager.kt
git commit -m "feat: add MemoryManager orchestrator with three write triggers and remember-this flow"
```

---

## Task 7: Conversation Segment and Message Store

**Goal:** Implement date-segmented conversation storage and the persistent message store.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/conversation/ConversationSegment.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/conversation/MessageStore.kt`

- [ ] **Step 1: Create ConversationSegment.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/conversation/ConversationSegment.kt`:

```kotlin
package org.dollos.ai.conversation

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A single message in a conversation.
 */
data class Message(
    val id: String,
    val role: Role,
    val content: String,
    val timestamp: Long = Instant.now().toEpochMilli(),
    val tokenCount: Int = 0
) {
    enum class Role {
        USER, ASSISTANT, SYSTEM
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("role", role.name)
            put("content", content)
            put("timestamp", timestamp)
            put("tokenCount", tokenCount)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Message {
            return Message(
                id = json.getString("id"),
                role = Role.valueOf(json.getString("role")),
                content = json.getString("content"),
                timestamp = json.getLong("timestamp"),
                tokenCount = json.optInt("tokenCount", 0)
            )
        }
    }
}

/**
 * A date-based conversation segment.
 * Each day gets its own segment. Users scroll up to see history.
 * No manual "new conversation" action needed.
 */
data class ConversationSegment(
    val date: LocalDate,
    val messages: MutableList<Message> = mutableListOf(),
    var summary: String? = null   // Set when this segment has been compressed
) {
    /**
     * Total estimated token count for all messages in this segment.
     */
    fun totalTokens(): Int {
        return messages.sumOf { it.tokenCount }
    }

    /**
     * Add a message to this segment.
     */
    fun addMessage(message: Message) {
        messages.add(message)
    }

    /**
     * Get the date of this segment as a string (YYYY-MM-DD).
     */
    fun dateString(): String = date.toString()

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("date", date.toString())
            put("summary", summary ?: JSONObject.NULL)
            val msgArray = JSONArray()
            messages.forEach { msgArray.put(it.toJson()) }
            put("messages", msgArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ConversationSegment {
            val date = LocalDate.parse(json.getString("date"))
            val summary = if (json.isNull("summary")) null else json.getString("summary")
            val msgArray = json.getJSONArray("messages")
            val messages = mutableListOf<Message>()
            for (i in 0 until msgArray.length()) {
                messages.add(Message.fromJson(msgArray.getJSONObject(i)))
            }
            return ConversationSegment(date, messages, summary)
        }

        /**
         * Determine the segment date for a given timestamp.
         */
        fun dateForTimestamp(timestampMs: Long): LocalDate {
            return Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }
    }
}
```

- [ ] **Step 2: Create MessageStore.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/conversation/MessageStore.kt`:

```kotlin
package org.dollos.ai.conversation

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Persists conversation messages to disk as JSON files.
 *
 * Storage layout:
 *   /data/user/0/org.dollos.ai/files/conversations/
 *     2026-03-19.json    -- one file per date segment
 *     2026-03-18.json
 *     ...
 */
class MessageStore(private val rootDir: File) {

    companion object {
        private const val TAG = "MessageStore"
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    init {
        rootDir.mkdirs()
    }

    /**
     * Save a conversation segment to disk.
     */
    fun saveSegment(segment: ConversationSegment) {
        val file = segmentFile(segment.date)
        try {
            file.writeText(segment.toJson().toString(2))
            Log.d(TAG, "Saved segment ${segment.dateString()} (${segment.messages.size} messages)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save segment ${segment.dateString()}", e)
            throw e
        }
    }

    /**
     * Load a conversation segment from disk.
     * Returns null if the segment file does not exist.
     */
    fun loadSegment(date: LocalDate): ConversationSegment? {
        val file = segmentFile(date)
        if (!file.exists()) return null

        return try {
            val json = JSONObject(file.readText())
            ConversationSegment.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load segment $date", e)
            null
        }
    }

    /**
     * Load today's segment, creating a new one if it doesn't exist.
     */
    fun loadOrCreateToday(): ConversationSegment {
        val today = LocalDate.now()
        return loadSegment(today) ?: ConversationSegment(today)
    }

    /**
     * Append a single message to today's segment.
     * Loads the segment, adds the message, and saves back.
     */
    fun appendMessage(message: Message) {
        val date = ConversationSegment.dateForTimestamp(message.timestamp)
        val segment = loadSegment(date) ?: ConversationSegment(date)
        segment.addMessage(message)
        saveSegment(segment)
    }

    /**
     * List all available segment dates, sorted descending (most recent first).
     */
    fun listSegmentDates(): List<LocalDate> {
        val files = rootDir.listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        } ?: return emptyList()

        return files.mapNotNull { file ->
            try {
                LocalDate.parse(file.nameWithoutExtension, DATE_FORMAT)
            } catch (e: Exception) {
                null
            }
        }.sortedDescending()
    }

    /**
     * Load multiple segments for conversation history display.
     * Returns segments in chronological order (oldest first).
     *
     * @param days Number of days to load from today.
     */
    fun loadRecentSegments(days: Int): List<ConversationSegment> {
        val today = LocalDate.now()
        val segments = mutableListOf<ConversationSegment>()
        for (i in days - 1 downTo 0) {
            val date = today.minusDays(i.toLong())
            loadSegment(date)?.let { segments.add(it) }
        }
        return segments
    }

    /**
     * Delete a segment file.
     */
    fun deleteSegment(date: LocalDate): Boolean {
        val file = segmentFile(date)
        if (!file.exists()) return false
        return file.delete()
    }

    /**
     * Get the total storage size of all conversation files in bytes.
     */
    fun totalStorageBytes(): Long {
        val files = rootDir.listFiles() ?: return 0
        return files.sumOf { it.length() }
    }

    private fun segmentFile(date: LocalDate): File {
        return File(rootDir, "${date.format(DATE_FORMAT)}.json")
    }
}
```

- [ ] **Step 3: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/conversation/ConversationSegment.kt
git add src/org/dollos/ai/conversation/MessageStore.kt
git commit -m "feat: add date-segmented conversation storage and message persistence"
```

---

## Task 8: Context Compressor

**Goal:** Implement proactive context compression that runs asynchronously using the foreground model when context reaches 70-80% capacity.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/conversation/ContextCompressor.kt`

- [ ] **Step 1: Create ContextCompressor.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/conversation/ContextCompressor.kt`:

```kotlin
package org.dollos.ai.conversation

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Proactive context compression.
 *
 * When context usage reaches the threshold (70-80%), this compressor:
 *   1. Snapshots current conversation for background compression
 *   2. Immediately truncates old messages in foreground (keeps recent N)
 *   3. Runs compression in parallel using foreground model (async, non-blocking)
 *   4. When compression completes, merges: summary + new messages accumulated during compression
 *
 * Compression produces:
 *   - A summary of the compressed conversation
 *   - Extracted facts that get written to memory (via MemoryManager)
 */
class ContextCompressor(
    private val contextWindowSize: Int = 128_000,       // model context window in tokens
    private val compressionThreshold: Float = 0.75f,    // trigger at 75% of context window
    private val keepRecentMessages: Int = 10            // messages to keep after truncation
) {
    companion object {
        private const val TAG = "ContextCompressor"
    }

    /**
     * Callback to send compression request to the LLM.
     * Input: list of messages to compress.
     * Output: CompressionResult (summary + extracted facts).
     */
    var compressWithLLM: (suspend (List<Message>) -> CompressionResult)? = null

    /**
     * Callback invoked when compression completes.
     * Called with the summary and extracted facts so the caller can:
     *   - Prepend the summary to the conversation context
     *   - Write extracted facts to memory
     */
    var onCompressionComplete: ((CompressionResult) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var compressionJob: Job? = null
    private val mutex = Mutex()
    private var isCompressing = false

    /**
     * Check if context usage has reached the compression threshold.
     *
     * @param currentTokenCount Current total token count in the conversation context.
     * @return True if compression should be triggered.
     */
    fun shouldCompress(currentTokenCount: Int): Boolean {
        if (isCompressing) return false
        return currentTokenCount >= (contextWindowSize * compressionThreshold).toInt()
    }

    /**
     * Trigger compression on the given messages.
     *
     * Returns the messages to keep in the foreground (recent messages only).
     * Compression runs in the background asynchronously.
     *
     * @param allMessages All current conversation messages.
     * @return TruncationResult containing the messages to keep immediately.
     */
    fun triggerCompression(allMessages: List<Message>): TruncationResult {
        if (isCompressing) {
            Log.w(TAG, "Compression already in progress, skipping")
            return TruncationResult(allMessages, emptyList())
        }

        if (allMessages.size <= keepRecentMessages) {
            Log.d(TAG, "Not enough messages to compress (${allMessages.size})")
            return TruncationResult(allMessages, emptyList())
        }

        // Split: old messages to compress, recent messages to keep
        val splitPoint = allMessages.size - keepRecentMessages
        val messagesToCompress = allMessages.subList(0, splitPoint).toList()
        val messagesToKeep = allMessages.subList(splitPoint, allMessages.size).toList()

        Log.i(TAG, "Triggering compression: ${messagesToCompress.size} messages to compress, " +
            "${messagesToKeep.size} messages kept")

        // Start async compression
        isCompressing = true
        compressionJob = scope.launch {
            try {
                val result = compressAsync(messagesToCompress)
                mutex.withLock {
                    isCompressing = false
                }
                onCompressionComplete?.invoke(result)
                Log.i(TAG, "Compression complete: summary ${result.summary.length} chars, " +
                    "${result.extractedFacts.size} facts extracted")
            } catch (e: Exception) {
                mutex.withLock {
                    isCompressing = false
                }
                Log.e(TAG, "Compression failed", e)
            }
        }

        return TruncationResult(messagesToKeep, messagesToCompress)
    }

    /**
     * Run compression using the foreground model.
     * This runs asynchronously and does not block the conversation.
     */
    private suspend fun compressAsync(messages: List<Message>): CompressionResult {
        val llmCompress = compressWithLLM
            ?: throw IllegalStateException("compressWithLLM callback not set")

        return llmCompress(messages)
    }

    /**
     * Cancel any in-progress compression.
     */
    fun cancelCompression() {
        compressionJob?.cancel()
        isCompressing = false
        Log.i(TAG, "Compression cancelled")
    }

    /**
     * Whether compression is currently in progress.
     */
    fun isCompressionInProgress(): Boolean = isCompressing

    /**
     * Build the system prompt for the compression LLM call.
     * This is the prompt sent to the foreground model to compress conversation history.
     */
    fun buildCompressionPrompt(messages: List<Message>): String {
        val conversationText = messages.joinToString("\n") { msg ->
            "${msg.role.name}: ${msg.content}"
        }

        return """You are a conversation summarizer. Your task is to:

1. Create a concise summary of the following conversation that preserves all important context, decisions, and ongoing topics. The summary should allow the conversation to continue naturally.

2. Extract any important facts, preferences, or decisions that should be remembered long-term. Output these as a JSON array of strings under the key "facts".

Respond in this exact JSON format:
{
  "summary": "your summary here",
  "facts": ["fact 1", "fact 2", ...]
}

--- CONVERSATION TO COMPRESS ---

$conversationText

--- END ---"""
    }
}

/**
 * Result of immediate truncation (before async compression completes).
 */
data class TruncationResult(
    /** Messages to keep in the foreground context. */
    val keptMessages: List<Message>,
    /** Messages sent for async compression. */
    val compressedMessages: List<Message>
)

/**
 * Result of async compression by the LLM.
 */
data class CompressionResult(
    /** Summary of the compressed conversation. */
    val summary: String,
    /** Facts extracted from the conversation for memory storage. */
    val extractedFacts: List<String>
)
```

- [ ] **Step 2: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/conversation/ContextCompressor.kt
git commit -m "feat: add async context compressor with foreground model compression"
```

---

## Task 9: Conversation Manager

**Goal:** Implement the top-level conversation manager that ties together message storage, context window management, and compression.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/conversation/ConversationManager.kt`

- [ ] **Step 1: Create ConversationManager.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/conversation/ConversationManager.kt`:

```kotlin
package org.dollos.ai.conversation

import android.content.Context
import android.util.Log
import org.dollos.ai.memory.MemoryManager
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Top-level conversation manager.
 *
 * Manages conversation state, history, context window, and coordinates
 * with MemoryManager for memory writes and context enrichment.
 *
 * Conversations are date-segmented. Each day is a separate segment.
 * Context compression triggers automatically at 70-80% capacity.
 */
class ConversationManager(
    private val context: Context,
    private val memoryManager: MemoryManager,
    contextWindowSize: Int = 128_000
) {
    companion object {
        private const val TAG = "ConversationManager"
        private const val ROUGH_CHARS_PER_TOKEN = 4
    }

    private val conversationsDir = File(context.filesDir, "conversations")
    val messageStore = MessageStore(conversationsDir)
    val compressor = ContextCompressor(contextWindowSize = contextWindowSize)

    /** Current active messages in the context window. */
    private val activeMessages = mutableListOf<Message>()

    /** Summary from last compression (prepended to context). */
    private var compressionSummary: String? = null

    /** Running token count estimate for the active context. */
    private var estimatedTokenCount: Int = 0

    /**
     * Initialize the conversation manager. Call on service startup.
     */
    fun init() {
        // Load today's segment
        val today = messageStore.loadOrCreateToday()
        activeMessages.clear()
        activeMessages.addAll(today.messages)
        compressionSummary = today.summary
        recalculateTokenCount()

        // Set up compression callbacks
        compressor.onCompressionComplete = { result ->
            onCompressionComplete(result)
        }

        Log.i(TAG, "Initialized with ${activeMessages.size} messages, " +
            "~$estimatedTokenCount tokens estimated")
    }

    /**
     * Add a user message to the conversation.
     * Persists to disk and checks for compression trigger.
     *
     * @return The created Message object.
     */
    fun addUserMessage(content: String): Message {
        val message = Message(
            id = generateMessageId(),
            role = Message.Role.USER,
            content = content,
            tokenCount = estimateTokens(content)
        )

        activeMessages.add(message)
        messageStore.appendMessage(message)
        estimatedTokenCount += message.tokenCount

        // Reset idle timer on user activity
        memoryManager.resetIdleTimer()

        // Check if compression is needed
        checkCompressionThreshold()

        return message
    }

    /**
     * Add an assistant response to the conversation.
     * Persists to disk and checks for compression trigger.
     *
     * @return The created Message object.
     */
    fun addAssistantMessage(content: String, tokenCount: Int = 0): Message {
        val actualTokenCount = if (tokenCount > 0) tokenCount else estimateTokens(content)
        val message = Message(
            id = generateMessageId(),
            role = Message.Role.ASSISTANT,
            content = content,
            tokenCount = actualTokenCount
        )

        activeMessages.add(message)
        messageStore.appendMessage(message)
        estimatedTokenCount += message.tokenCount

        // Reset idle timer on assistant activity
        memoryManager.resetIdleTimer()

        // Check if compression is needed
        checkCompressionThreshold()

        return message
    }

    /**
     * Build the full context to send to the LLM.
     * Includes: compression summary (if any) + active messages.
     *
     * Note: Memory context (Tier 1, 2, 3) is injected separately by the
     * system prompt builder, not here. This only handles conversation messages.
     */
    fun buildConversationContext(): List<Message> {
        val context = mutableListOf<Message>()

        // Prepend compression summary as a system message if available
        if (!compressionSummary.isNullOrBlank()) {
            context.add(Message(
                id = "compression-summary",
                role = Message.Role.SYSTEM,
                content = "Previous conversation summary:\n${compressionSummary}",
                tokenCount = estimateTokens(compressionSummary!!)
            ))
        }

        context.addAll(activeMessages)
        return context
    }

    /**
     * Get the estimated current token usage.
     */
    fun getEstimatedTokenCount(): Int = estimatedTokenCount

    /**
     * Get today's conversation segment.
     */
    fun getTodaySegment(): ConversationSegment {
        return ConversationSegment(
            date = LocalDate.now(),
            messages = activeMessages.toMutableList(),
            summary = compressionSummary
        )
    }

    /**
     * Load historical conversation segments for display.
     *
     * @param days Number of past days to load.
     * @return List of segments in chronological order.
     */
    fun loadHistory(days: Int): List<ConversationSegment> {
        return messageStore.loadRecentSegments(days)
    }

    /**
     * Clear today's conversation. For testing/debugging.
     */
    fun clearToday() {
        activeMessages.clear()
        compressionSummary = null
        estimatedTokenCount = 0
        messageStore.saveSegment(ConversationSegment(LocalDate.now()))
        Log.i(TAG, "Cleared today's conversation")
    }

    // -- Internal --

    private fun checkCompressionThreshold() {
        if (compressor.shouldCompress(estimatedTokenCount)) {
            Log.i(TAG, "Context threshold reached ($estimatedTokenCount tokens), " +
                "triggering compression")

            val result = compressor.triggerCompression(activeMessages.toList())

            // Immediately update foreground context with truncated messages
            activeMessages.clear()
            activeMessages.addAll(result.keptMessages)
            recalculateTokenCount()

            Log.i(TAG, "Truncated to ${activeMessages.size} messages, " +
                "~$estimatedTokenCount tokens. Compression running async.")
        }
    }

    private fun onCompressionComplete(result: CompressionResult) {
        // Merge: new summary + messages accumulated since truncation
        val previousSummary = compressionSummary
        compressionSummary = if (previousSummary.isNullOrBlank()) {
            result.summary
        } else {
            "$previousSummary\n\n---\n\n${result.summary}"
        }

        // Save the updated segment with summary
        val todaySegment = ConversationSegment(
            date = LocalDate.now(),
            messages = activeMessages.toMutableList(),
            summary = compressionSummary
        )
        messageStore.saveSegment(todaySegment)

        // Write extracted facts to memory
        if (result.extractedFacts.isNotEmpty()) {
            memoryManager.onContextThresholdReached(result.extractedFacts)
        }

        recalculateTokenCount()
        Log.i(TAG, "Compression merged. Summary: ${result.summary.length} chars, " +
            "facts: ${result.extractedFacts.size}")
    }

    private fun recalculateTokenCount() {
        var total = 0
        if (!compressionSummary.isNullOrBlank()) {
            total += estimateTokens(compressionSummary!!)
        }
        total += activeMessages.sumOf { it.tokenCount }
        estimatedTokenCount = total
    }

    private fun estimateTokens(text: String): Int {
        // Rough estimate: ~4 characters per token for English text
        return (text.length / ROUGH_CHARS_PER_TOKEN).coerceAtLeast(1)
    }

    private fun generateMessageId(): String {
        return "${Instant.now().toEpochMilli()}-${(Math.random() * 10000).toInt()}"
    }
}
```

- [ ] **Step 2: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/conversation/ConversationManager.kt
git commit -m "feat: add ConversationManager with context window management and compression integration"
```

---

## Task 10: Memory Export/Import

**Goal:** Implement memory export and import via ParcelFileDescriptor for Android scoped storage compliance.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MemoryExporter.kt`

- [ ] **Step 1: Create MemoryExporter.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MemoryExporter.kt`:

```kotlin
package org.dollos.ai.memory

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Exports and imports memory as a ZIP archive via ParcelFileDescriptor.
 *
 * Uses ParcelFileDescriptor instead of file paths to comply with
 * Android scoped storage. The caller (Settings UI or AIDL client)
 * opens a document via SAF and passes the PFD to these methods.
 *
 * Export format: ZIP containing all Markdown memory files with
 * their relative paths preserved.
 *
 * Import: extracts ZIP into the memory root directory, overwriting
 * existing files. Triggers a full ObjectBox index rebuild after import.
 */
class MemoryExporter(
    private val rootDir: File,
    private val store: MarkdownMemoryStore,
    private val objectBoxIndex: ObjectBoxIndex?
) {
    companion object {
        private const val TAG = "MemoryExporter"
        private const val EXPORT_BUFFER_SIZE = 8192
    }

    /**
     * Export all memory files to a ZIP archive via ParcelFileDescriptor.
     *
     * @param fd ParcelFileDescriptor opened for writing (from SAF / content resolver).
     */
    fun exportMemory(fd: ParcelFileDescriptor) {
        val outputStream = FileOutputStream(fd.fileDescriptor)
        val zipOut = ZipOutputStream(outputStream)

        try {
            val files = store.listAllMemoryFiles()
            Log.i(TAG, "Exporting ${files.size} memory files")

            for ((relativePath, file) in files) {
                val entry = ZipEntry(relativePath)
                zipOut.putNextEntry(entry)

                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(EXPORT_BUFFER_SIZE)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        zipOut.write(buffer, 0, bytesRead)
                    }
                }

                zipOut.closeEntry()
                Log.d(TAG, "Exported: $relativePath")
            }

            Log.i(TAG, "Export complete: ${files.size} files")
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            throw e
        } finally {
            zipOut.close()
            fd.close()
        }
    }

    /**
     * Import memory files from a ZIP archive via ParcelFileDescriptor.
     * Overwrites existing files. Triggers index rebuild after import.
     *
     * @param fd ParcelFileDescriptor opened for reading (from SAF / content resolver).
     */
    suspend fun importMemory(fd: ParcelFileDescriptor) {
        val inputStream = FileInputStream(fd.fileDescriptor)
        val zipIn = ZipInputStream(inputStream)

        try {
            var entry = zipIn.nextEntry
            var fileCount = 0

            while (entry != null) {
                if (entry.isDirectory) {
                    val dir = File(rootDir, entry.name)
                    dir.mkdirs()
                } else {
                    // Validate path to prevent zip slip attack
                    val targetFile = File(rootDir, entry.name)
                    val canonicalRoot = rootDir.canonicalPath
                    val canonicalTarget = targetFile.canonicalPath
                    if (!canonicalTarget.startsWith(canonicalRoot)) {
                        Log.e(TAG, "Zip slip detected, skipping: ${entry.name}")
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }

                    // Ensure parent directory exists
                    targetFile.parentFile?.mkdirs()

                    FileOutputStream(targetFile).use { fos ->
                        val buffer = ByteArray(EXPORT_BUFFER_SIZE)
                        var bytesRead: Int
                        while (zipIn.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                    }

                    fileCount++
                    Log.d(TAG, "Imported: ${entry.name}")
                }

                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }

            Log.i(TAG, "Import complete: $fileCount files")

            // Rebuild ObjectBox index from imported Markdown files
            if (objectBoxIndex != null && objectBoxIndex.isAvailable()) {
                Log.i(TAG, "Rebuilding index after import...")
                objectBoxIndex.rebuildFromFiles(store)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            throw e
        } finally {
            zipIn.close()
            fd.close()
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/memory/MemoryExporter.kt
git commit -m "feat: add memory export/import via ParcelFileDescriptor with zip slip protection"
```

---

## Task 11: ObjectBox and ONNX Prebuilt Dependencies

**Goal:** Add ObjectBox and ONNX Runtime as prebuilt dependencies for the AOSP build system.

**Files:**
- Create: `prebuilts/dollos/objectbox/Android.bp`
- Create: `prebuilts/dollos/onnxruntime/Android.bp`
- Modify: `packages/apps/DollOSAIService/Android.bp`

- [ ] **Step 1: Create ObjectBox prebuilt Android.bp**

Create `prebuilts/dollos/objectbox/Android.bp`:

```
// ObjectBox on-device vector database for Android
// https://objectbox.io/the-on-device-vector-database-for-android-and-java/
//
// Download JARs from Maven Central:
//   io.objectbox:objectbox-android:4.0.3
//   io.objectbox:objectbox-kotlin:4.0.3
//   io.objectbox:objectbox-linux-arm64:4.0.3 (native lib)

java_import {
    name: "objectbox-android",
    jars: ["objectbox-android-4.0.3.jar"],
    sdk_version: "current",
}

java_import {
    name: "objectbox-kotlin",
    jars: ["objectbox-kotlin-4.0.3.jar"],
    sdk_version: "current",
}

cc_prebuilt_library_shared {
    name: "libobjectbox-jni",
    srcs: ["arm64-v8a/libobjectbox-jni.so"],
    target: {
        android_arm64: {
            srcs: ["arm64-v8a/libobjectbox-jni.so"],
        },
    },
    strip: {
        none: true,
    },
    check_elf_files: false,
}
```

- [ ] **Step 2: Create ONNX Runtime prebuilt Android.bp**

Create `prebuilts/dollos/onnxruntime/Android.bp`:

```
// ONNX Runtime for Android (local embedding inference)
// https://onnxruntime.ai/
//
// Download AAR from Maven Central:
//   com.microsoft.onnxruntime:onnxruntime-android:1.17.0
//
// For v1, this is a placeholder -- LocalEmbeddingProvider returns zero vectors.
// Actual ONNX inference will be implemented in a future plan.

android_library_import {
    name: "onnxruntime-android",
    aars: ["onnxruntime-android-1.17.0.aar"],
    sdk_version: "current",
}
```

- [ ] **Step 3: Update DollOSAIService Android.bp**

Add ObjectBox and ONNX Runtime to DollOSAIService's `static_libs` in `packages/apps/DollOSAIService/Android.bp`:

Add to the `static_libs` array:

```
    // Memory system dependencies
    "objectbox-android",
    "objectbox-kotlin",
    "onnxruntime-android",
```

Add to `required` array (for native lib):

```
    "libobjectbox-jni",
```

- [ ] **Step 4: Note on ObjectBox annotation processor**

ObjectBox uses compile-time annotation processing to generate `MyObjectBox` and entity property classes (`MemoryChunk_`). In AOSP's Soong build system, Java/Kotlin annotation processors are configured differently than Gradle.

If the ObjectBox annotation processor cannot be integrated into Soong:
1. Create a standalone Gradle project that compiles the ObjectBox entities
2. Copy the generated `MyObjectBox.java` and `MemoryChunk_.java` into the source tree
3. Include them as regular source files

The generated files would go in:
- `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MyObjectBox.java`
- `packages/apps/DollOSAIService/src/org/dollos/ai/memory/MemoryChunk_.java`

This step must be resolved during the actual build. Document the resolution in the verification log.

- [ ] **Step 5: Download dependency JARs and AARs**

```bash
cd ~/Desktop/DollOS-build

# ObjectBox
mkdir -p prebuilts/dollos/objectbox/arm64-v8a
cd prebuilts/dollos/objectbox
# Download from Maven Central:
# wget https://repo1.maven.org/maven2/io/objectbox/objectbox-android/4.0.3/objectbox-android-4.0.3.jar
# wget https://repo1.maven.org/maven2/io/objectbox/objectbox-kotlin/4.0.3/objectbox-kotlin-4.0.3.jar
# Native lib from objectbox-linux-arm64 or extracted from the Android AAR

# ONNX Runtime
mkdir -p prebuilts/dollos/onnxruntime
cd prebuilts/dollos/onnxruntime
# wget https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.17.0/onnxruntime-android-1.17.0.aar
```

- [ ] **Step 6: Commit**

```bash
cd ~/Desktop/DollOS-build
git add prebuilts/dollos/objectbox/Android.bp
git add prebuilts/dollos/onnxruntime/Android.bp
git add packages/apps/DollOSAIService/Android.bp
git commit -m "feat: add ObjectBox and ONNX Runtime prebuilt dependencies"
```

---

## Task 12: Build, Flash, and Verify

**Goal:** Verify the memory system and conversation engine compile, integrate correctly with DollOSAIService, and function on device.

- [ ] **Step 1: Build**

```bash
cd ~/Desktop/DollOS-build
source build/envsetup.sh
lunch dollos_bluejay-cur-userdebug
m -j$(nproc)
```

If build fails due to ObjectBox annotation processing, follow the workaround in Task 11 Step 4 (pre-generate entity classes).

If build fails due to missing ObjectBox or ONNX JARs/AARs, download them as described in Task 11 Step 5.

- [ ] **Step 2: Flash**

```bash
adb reboot bootloader
# wait 10s
cd out/target/product/bluejay
fastboot flashall -w
```

- [ ] **Step 3: Verify memory file creation**

```bash
# After boot + OOBE + initial conversation
adb shell run-as org.dollos.ai ls -la files/memory/
# Expected: MEMORY.md exists
# Expected: memory/ directory exists with people/, topics/, decisions/ subdirs

adb shell run-as org.dollos.ai cat files/memory/MEMORY.md
# Expected: initial core memory content
```

- [ ] **Step 4: Verify conversation persistence**

```bash
# After sending a test message via the AI service
adb shell run-as org.dollos.ai ls -la files/conversations/
# Expected: YYYY-MM-DD.json for today

adb shell run-as org.dollos.ai cat files/conversations/$(date +%Y-%m-%d).json
# Expected: JSON with messages array containing the test message
```

- [ ] **Step 5: Verify memory write queue**

```bash
# Check logs for write queue activity
adb shell logcat -d | grep "MemoryWriteQueue\|MarkdownMemoryStore\|MemoryManager"
# Expected: "Write queue started", write operations logged
```

- [ ] **Step 6: Verify search fallback (no ObjectBox)**

If ObjectBox initialization fails on first run, the search should still work via substring fallback:

```bash
adb shell logcat -d | grep "MemorySearchEngine\|ObjectBoxIndex"
# If ObjectBox init failed: "ObjectBox initialization failed, search will use fallback"
# Search calls should show: "Substring search returned N results"
```

- [ ] **Step 7: Verify memory export via AIDL**

```bash
# Test exportMemory via adb (requires a test client or adb shell service call)
adb shell logcat -d | grep "MemoryExporter"
```

- [ ] **Step 8: Commit verification results**

Create `docs/verification-plan-b.md` with test results and any issues encountered.

```bash
cd ~/Desktop/DollOS
git add docs/verification-plan-b.md
git commit -m "docs: add Plan B verification results"
```

---

## Notes

### Integration with Plan A

Plan B depends on Plan A (DollOSAIService). When integrating:
1. `DollOSAIApp.onCreate()` should create and init `MemoryManager` and `ConversationManager`
2. `IDollOSAIService.sendMessage()` should use `ConversationManager.addUserMessage()` and pass memory context from `MemoryManager.buildMemoryContext()`
3. `IDollOSAIService.searchMemory()` should delegate to `MemorySearchEngine.search()`
4. `IDollOSAIService.exportMemory()` / `importMemory()` should delegate to `MemoryExporter`
5. `IDollOSAIService.confirmMemoryWrite()` should call `MemoryManager.confirmRememberThis()`
6. `IDollOSAIService.setEmbeddingSource()` should call `MemoryManager.setEmbeddingSource()`
7. LLM response handler should use `ConversationManager.addAssistantMessage()`
8. System prompt builder should include `MemoryManager.buildMemoryContext()` and `MemoryManager.searchRelevantMemory(query)`

### Integration with Plan C

After Plan C is complete:
- Background memory operations (idle extraction, index rebuild) will appear as tasks in the AI Task Manager
- `pauseAll()` should pause the memory write queue and any in-progress compression

### ObjectBox Considerations

ObjectBox for Android uses native JNI libraries. On AOSP:
- The native `.so` must be available at runtime (included via `required` in Android.bp or bundled in the APK)
- ObjectBox annotation processor generates `MyObjectBox` -- if Soong can't run it, pre-generate from a Gradle project
- ObjectBox database files are stored in the app's internal storage (no special permissions needed)
- If ObjectBox fails to initialize, the system degrades gracefully to substring search

### Token Estimation

The `ContextCompressor` and `ConversationManager` use a rough ~4 chars/token estimate. When integrated with Plan A's LLM client, actual token counts from API responses should be used instead via `Message.tokenCount`.

### Future Improvements (Out of Scope)

- Actual ONNX Runtime inference for local embeddings (LocalEmbeddingProvider currently returns zero vectors)
- Smart chunking strategies (semantic boundaries, overlap)
- Memory deduplication and conflict resolution
- Memory aging and archival
- Cross-device memory sync
