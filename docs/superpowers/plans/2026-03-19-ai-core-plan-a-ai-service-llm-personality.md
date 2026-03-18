# AI Core Plan A: DollOSAIService + LLM Client + Personality System

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build DollOSAIService as a new privileged Android system service (normal app uid, platform cert) with AIDL interfaces for AI operations, a multi-model LLM client with streaming support and budget controls, and a 5-field personality system. Update OOBE SetupWizard to bind to DollOSAIService for API key and personality configuration. Integrate with Plan C's DollOSService for agent action execution.

**Architecture:** DollOSAIService runs as a separate privileged app (package `org.dollos.ai`) with normal app uid (no `sharedUserId=system`) to avoid storage issues. It communicates with clients via AIDL (`IDollOSAIService`, `IDollOSAICallback`). LLM API calls use `java.net.HttpURLConnection` (guaranteed available in AOSP, no external deps). Config stored in `SharedPreferences` via device-protected storage. Agent actions are delegated to DollOSService via Binder IPC (`executeSystemAction`).

**Tech Stack:** Kotlin, AIDL (Binder IPC), java.net.HttpURLConnection, SharedPreferences (device-protected storage), Android Notification API, Soong build system (Android.bp)

---

## File Structure

### DollOSAIService (new app)

```
packages/apps/DollOSAIService/
  Android.bp
  AndroidManifest.xml
  privapp-permissions-dollos-ai.xml
  aidl/org/dollos/ai/
    IDollOSAIService.aidl
    IDollOSAICallback.aidl
  src/org/dollos/ai/
    DollOSAIApp.kt
    DollOSAIService.kt
    DollOSAIServiceImpl.kt
    llm/
      LLMClient.kt
      LLMProvider.kt
      ClaudeProvider.kt
      GrokProvider.kt
      OpenAIProvider.kt
      CustomProvider.kt
      LLMResponse.kt
      StreamingCallback.kt
    personality/
      PersonalityManager.kt
      SystemPromptBuilder.kt
    usage/
      UsageTracker.kt
      BudgetManager.kt
```

### DollOSSetupWizard (modify existing)

```
packages/apps/DollOSSetupWizard/
  Android.bp                       -- add dollos-ai-aidl dependency
  src/org/dollos/setup/
    SetupWizardActivity.kt         -- add DollOSAIService binding
    ApiKeyPage.kt                  -- switch to DollOSAIService calls
    PersonalityPage.kt             -- switch to DollOSAIService calls, add 5 fields
  res/layout/
    page_api_key.xml               -- add foreground/background model selection
    page_personality.xml           -- add backstory, directive, dynamism, address, language
```

### Vendor Config (modify existing)

```
vendor/dollos/
  dollos_bluejay.mk               -- add DollOSAIService to PRODUCT_PACKAGES + privapp perms
```

---

## Task 1: AIDL Interfaces

**Goal:** Define the two AIDL interfaces for DollOSAIService: the main service interface and the callback interface for streaming/async events.

**Files:**
- Create: `packages/apps/DollOSAIService/aidl/org/dollos/ai/IDollOSAICallback.aidl`
- Create: `packages/apps/DollOSAIService/aidl/org/dollos/ai/IDollOSAIService.aidl`

- [ ] **Step 1: Create IDollOSAICallback.aidl**

Create `packages/apps/DollOSAIService/aidl/org/dollos/ai/IDollOSAICallback.aidl`:

```aidl
package org.dollos.ai;

interface IDollOSAICallback {
    // Streaming response -- token-by-token delivery
    void onToken(String token);
    void onResponseComplete(String fullResponse);
    void onResponseError(String errorCode, String message);

    // Agent -- confirmation and execution results
    void onActionConfirmRequired(String actionId, String actionName, String description);
    void onActionExecuted(String actionId, boolean success, String resultMessage);

    // Task updates -- JSON list of active tasks
    void onTaskListUpdated(String tasksJson);

    // Memory -- user confirmation for "remember this"
    void onMemoryConfirmRequired(String formattedMemory);
}
```

- [ ] **Step 2: Create IDollOSAIService.aidl**

Create `packages/apps/DollOSAIService/aidl/org/dollos/ai/IDollOSAIService.aidl`:

```aidl
package org.dollos.ai;

import org.dollos.ai.IDollOSAICallback;

interface IDollOSAIService {
    // Callback registration
    void registerCallback(IDollOSAICallback callback);
    void unregisterCallback(IDollOSAICallback callback);

    // Conversation
    void sendMessage(String message);
    void stopGeneration();
    boolean pauseAll();
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

    // Embedding Configuration
    void setEmbeddingSource(String source);
    String getEmbeddingSource();

    // Memory
    String searchMemory(String query);
    void exportMemory(in ParcelFileDescriptor fd);
    void importMemory(in ParcelFileDescriptor fd);
    void confirmMemoryWrite(boolean approved);

    // Usage
    String getUsageStats();
    void setWarningThreshold(long tokens, String period);
    void setHardLimit(long tokens, String period);

    // Agent confirmation
    void confirmAction(String actionId, boolean approved);

    // Task Manager
    String getActiveTasks();
    void cancelTask(String taskId);
}
```

- [ ] **Step 3: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add aidl/
git commit -m "feat: add IDollOSAIService and IDollOSAICallback AIDL interfaces"
```

---

## Task 2: Android.bp, AndroidManifest.xml, and Permissions

**Goal:** Create the Soong build file, Android manifest, and privapp permissions for DollOSAIService. The app uses normal app uid (no sharedUserId), platform cert, and privileged placement.

**Files:**
- Create: `packages/apps/DollOSAIService/Android.bp`
- Create: `packages/apps/DollOSAIService/AndroidManifest.xml`
- Create: `packages/apps/DollOSAIService/privapp-permissions-dollos-ai.xml`

- [ ] **Step 1: Create Android.bp**

Create `packages/apps/DollOSAIService/Android.bp`:

```
java_library {
    name: "dollos-ai-aidl",
    srcs: [
        "aidl/**/*.aidl",
    ],
    aidl: {
        local_include_dirs: ["aidl"],
    },
    platform_apis: true,
}

android_app {
    name: "DollOSAIService",
    srcs: [
        "src/**/*.kt",
    ],
    platform_apis: true,
    privileged: true,
    certificate: "platform",
    static_libs: [
        "androidx.core_core-ktx",
        "dollos-ai-aidl",
        "dollos-service-aidl",
    ],
    kotlincflags: ["-Xjvm-default=all"],
}
```

Note: `dollos-service-aidl` is included so DollOSAIService can bind to DollOSService and call `executeSystemAction` and `getAvailableActions`.

- [ ] **Step 2: Create AndroidManifest.xml**

Create `packages/apps/DollOSAIService/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="org.dollos.ai">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".DollOSAIApp"
        android:label="DollOS AI Service"
        android:persistent="true"
        android:directBootAware="true">

        <service
            android:name=".DollOSAIService"
            android:exported="true"
            android:enabled="true">
            <intent-filter>
                <action android:name="org.dollos.ai.IDollOSAIService" />
            </intent-filter>
        </service>

    </application>
</manifest>
```

- [ ] **Step 3: Create privapp-permissions-dollos-ai.xml**

Create `packages/apps/DollOSAIService/privapp-permissions-dollos-ai.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="org.dollos.ai">
        <permission name="android.permission.INTERNET" />
        <permission name="android.permission.RECEIVE_BOOT_COMPLETED" />
        <permission name="android.permission.FOREGROUND_SERVICE" />
        <permission name="android.permission.POST_NOTIFICATIONS" />
    </privapp-permissions>
</permissions>
```

- [ ] **Step 4: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add Android.bp AndroidManifest.xml privapp-permissions-dollos-ai.xml
git commit -m "feat: add Android.bp, manifest, and privapp permissions for DollOSAIService"
```

---

## Task 3: Application Class and Service Entry Point

**Goal:** Create the Application class (SharedPreferences init, notification channel) and the Service class (Binder binding, DollOSService connection for agent delegation).

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/DollOSAIApp.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/DollOSAIService.kt`

- [ ] **Step 1: Create DollOSAIApp.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/DollOSAIApp.kt`:

```kotlin
package org.dollos.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class DollOSAIApp : Application() {

    companion object {
        private const val TAG = "DollOSAIApp"
        const val VERSION = "0.1.0"
        const val PREFS_NAME = "dollos_ai_config"
        const val NOTIFICATION_CHANNEL_ID = "dollos_ai_errors"

        lateinit var instance: DollOSAIApp
            private set
        lateinit var prefs: SharedPreferences
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val deContext = createDeviceProtectedStorageContext()
        prefs = deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.i(TAG, "DollOS AI Application initialized, version $VERSION")
        Log.i(TAG, "SharedPreferences: ${prefs.all.size} entries")

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "AI Service Errors",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for API errors, rate limits, and budget alerts"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
        Log.i(TAG, "Notification channel created: $NOTIFICATION_CHANNEL_ID")
    }
}
```

- [ ] **Step 2: Create DollOSAIService.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/DollOSAIService.kt`:

```kotlin
package org.dollos.ai

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import org.dollos.service.IDollOSService

class DollOSAIService : Service() {

    companion object {
        private const val TAG = "DollOSAIService"
    }

    private lateinit var binder: DollOSAIServiceImpl
    var dollOSService: IDollOSService? = null
        private set
    private var isBoundToDollOS = false

    private val dollOSServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            dollOSService = IDollOSService.Stub.asInterface(service)
            Log.i(TAG, "Connected to DollOSService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dollOSService = null
            Log.w(TAG, "Disconnected from DollOSService")
        }
    }

    override fun onCreate() {
        super.onCreate()
        binder = DollOSAIServiceImpl(this)
        bindToDollOSService()
        Log.i(TAG, "DollOS AI Service created")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "DollOS AI Service bound")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBoundToDollOS) {
            unbindService(dollOSServiceConnection)
            isBoundToDollOS = false
        }
        Log.i(TAG, "DollOS AI Service destroyed")
    }

    private fun bindToDollOSService() {
        val intent = Intent("org.dollos.service.IDollOSService")
        intent.setPackage("org.dollos.service")
        isBoundToDollOS = bindService(intent, dollOSServiceConnection, Context.BIND_AUTO_CREATE)
        if (!isBoundToDollOS) {
            Log.e(TAG, "Failed to bind to DollOSService")
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/DollOSAIApp.kt src/org/dollos/ai/DollOSAIService.kt
git commit -m "feat: add DollOSAIApp and DollOSAIService entry point with DollOSService binding"
```

---

## Task 4: LLM Client -- Provider Interface and Response Types

**Goal:** Define the LLM provider abstraction, provider enum, response data classes, and streaming callback interface.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/llm/LLMClient.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/llm/LLMProvider.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/llm/LLMResponse.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/llm/StreamingCallback.kt`

- [ ] **Step 1: Create LLMProvider.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/llm/LLMProvider.kt`:

```kotlin
package org.dollos.ai.llm

enum class LLMProviderType(
    val displayName: String,
    val minTemperature: Float,
    val maxTemperature: Float
) {
    CLAUDE("Claude", 0.3f, 1.0f),
    GROK("Grok", 0.3f, 1.2f),
    OPENAI("OpenAI", 0.3f, 1.2f),
    CUSTOM("Custom", 0.0f, 2.0f);

    fun clampTemperature(dynamism: Float): Float {
        val temperature = minTemperature + (maxTemperature - minTemperature) * dynamism
        return temperature.coerceIn(minTemperature, maxTemperature)
    }

    companion object {
        fun fromString(name: String): LLMProviderType {
            return when (name.lowercase()) {
                "claude" -> CLAUDE
                "grok" -> GROK
                "openai" -> OPENAI
                "custom" -> CUSTOM
                else -> throw IllegalArgumentException("Unknown provider: $name")
            }
        }
    }
}

data class ModelConfig(
    val providerType: LLMProviderType,
    val apiKey: String,
    val model: String,
    val baseUrl: String = ""
)
```

- [ ] **Step 2: Create LLMResponse.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/llm/LLMResponse.kt`:

```kotlin
package org.dollos.ai.llm

data class LLMResponse(
    val content: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val model: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String = "stop"
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class LLMMessage(
    val role: String,
    val content: String
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: String
)
```

- [ ] **Step 3: Create StreamingCallback.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/llm/StreamingCallback.kt`:

```kotlin
package org.dollos.ai.llm

interface StreamingCallback {
    fun onToken(token: String)
    fun onComplete(response: LLMResponse)
    fun onError(errorCode: String, message: String)
}
```

- [ ] **Step 4: Create LLMClient.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/llm/LLMClient.kt`:

```kotlin
package org.dollos.ai.llm

interface LLMClient {
    val providerType: LLMProviderType

    fun sendBlocking(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        tools: List<ToolDefinition> = emptyList()
    ): LLMResponse

    fun sendStreaming(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        callback: StreamingCallback,
        tools: List<ToolDefinition> = emptyList()
    )

    fun cancelCurrent()
}
```

- [ ] **Step 5: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/llm/
git commit -m "feat: add LLM client interface, provider types, response models, and streaming callback"
```

---

## Task 5: LLM Provider Implementations

**Goal:** Implement concrete LLM providers for Claude, Grok, OpenAI, and Custom endpoints using `java.net.HttpURLConnection`. Each provider handles its own API format, streaming SSE parsing, and tool call extraction.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/llm/ClaudeProvider.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/llm/OpenAIProvider.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/llm/GrokProvider.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/llm/CustomProvider.kt`

- [ ] **Step 1: Create ClaudeProvider.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/llm/ClaudeProvider.kt`:

```kotlin
package org.dollos.ai.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class ClaudeProvider(private val config: ModelConfig) : LLMClient {

    companion object {
        private const val TAG = "ClaudeProvider"
        private const val BASE_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
    }

    override val providerType = LLMProviderType.CLAUDE
    private val cancelled = AtomicBoolean(false)
    private var currentConnection: HttpURLConnection? = null

    override fun sendBlocking(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        tools: List<ToolDefinition>
    ): LLMResponse {
        cancelled.set(false)
        val body = buildRequestBody(messages, systemPrompt, temperature, tools, stream = false)
        val conn = openConnection()
        currentConnection = conn

        try {
            writeBody(conn, body)
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw LLMException("HTTP $responseCode", errorBody)
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            return parseBlockingResponse(responseText)
        } finally {
            currentConnection = null
            conn.disconnect()
        }
    }

    override fun sendStreaming(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        callback: StreamingCallback,
        tools: List<ToolDefinition>
    ) {
        cancelled.set(false)
        val body = buildRequestBody(messages, systemPrompt, temperature, tools, stream = true)
        val conn = openConnection()
        currentConnection = conn

        try {
            writeBody(conn, body)
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                callback.onError("HTTP_$responseCode", errorBody)
                return
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val fullContent = StringBuilder()
            var inputTokens = 0L
            var outputTokens = 0L
            val toolCalls = mutableListOf<ToolCall>()
            var currentToolId = ""
            var currentToolName = ""
            val currentToolArgs = StringBuilder()
            var inToolUse = false

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (cancelled.get()) {
                    callback.onError("CANCELLED", "Request cancelled by user")
                    return
                }

                val data = line ?: continue
                if (!data.startsWith("data: ")) continue
                val payload = data.substring(6).trim()
                if (payload == "[DONE]" || payload.isEmpty()) continue

                val event = JSONObject(payload)
                val type = event.optString("type", "")

                when (type) {
                    "message_start" -> {
                        val usage = event.optJSONObject("message")?.optJSONObject("usage")
                        inputTokens = usage?.optLong("input_tokens", 0) ?: 0
                    }
                    "content_block_start" -> {
                        val block = event.optJSONObject("content_block")
                        val blockType = block?.optString("type", "")
                        if (blockType == "tool_use") {
                            inToolUse = true
                            currentToolId = block.optString("id", "")
                            currentToolName = block.optString("name", "")
                            currentToolArgs.clear()
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta")
                        val deltaType = delta?.optString("type", "")
                        if (deltaType == "text_delta") {
                            val text = delta.optString("text", "")
                            if (text.isNotEmpty()) {
                                fullContent.append(text)
                                callback.onToken(text)
                            }
                        } else if (deltaType == "input_json_delta") {
                            val partial = delta.optString("partial_json", "")
                            currentToolArgs.append(partial)
                        }
                    }
                    "content_block_stop" -> {
                        if (inToolUse) {
                            toolCalls.add(ToolCall(
                                id = currentToolId,
                                name = currentToolName,
                                arguments = currentToolArgs.toString()
                            ))
                            inToolUse = false
                        }
                    }
                    "message_delta" -> {
                        val usage = event.optJSONObject("usage")
                        outputTokens = usage?.optLong("output_tokens", 0) ?: 0
                    }
                }
            }

            val response = LLMResponse(
                content = fullContent.toString(),
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                model = config.model,
                toolCalls = toolCalls
            )
            callback.onComplete(response)
        } catch (e: Exception) {
            if (!cancelled.get()) {
                Log.e(TAG, "Streaming error", e)
                callback.onError("STREAM_ERROR", e.message ?: "Unknown streaming error")
            }
        } finally {
            currentConnection = null
            conn.disconnect()
        }
    }

    override fun cancelCurrent() {
        cancelled.set(true)
        currentConnection?.disconnect()
    }

    private fun openConnection(): HttpURLConnection {
        val url = URL(BASE_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", config.apiKey)
        conn.setRequestProperty("anthropic-version", API_VERSION)
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: JSONObject) {
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        tools: List<ToolDefinition>,
        stream: Boolean
    ): JSONObject {
        val body = JSONObject()
        body.put("model", config.model)
        body.put("max_tokens", 4096)
        body.put("temperature", temperature.toDouble())
        body.put("stream", stream)

        if (systemPrompt.isNotBlank()) {
            body.put("system", systemPrompt)
        }

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            messagesArray.put(msgObj)
        }
        body.put("messages", messagesArray)

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                val toolObj = JSONObject()
                toolObj.put("name", tool.name)
                toolObj.put("description", tool.description)
                toolObj.put("input_schema", JSONObject(tool.parameters))
                toolsArray.put(toolObj)
            }
            body.put("tools", toolsArray)
        }

        return body
    }

    private fun parseBlockingResponse(responseText: String): LLMResponse {
        val json = JSONObject(responseText)
        val content = json.optJSONArray("content")
        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()

        if (content != null) {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> textBuilder.append(block.optString("text", ""))
                    "tool_use" -> toolCalls.add(ToolCall(
                        id = block.optString("id", ""),
                        name = block.optString("name", ""),
                        arguments = block.optJSONObject("input")?.toString() ?: "{}"
                    ))
                }
            }
        }

        val usage = json.optJSONObject("usage")
        return LLMResponse(
            content = textBuilder.toString(),
            inputTokens = usage?.optLong("input_tokens", 0) ?: 0,
            outputTokens = usage?.optLong("output_tokens", 0) ?: 0,
            model = json.optString("model", config.model),
            toolCalls = toolCalls,
            finishReason = json.optString("stop_reason", "end_turn")
        )
    }
}

class LLMException(val errorCode: String, message: String) : Exception(message)
```

- [ ] **Step 2: Create OpenAIProvider.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/llm/OpenAIProvider.kt`:

```kotlin
package org.dollos.ai.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class OpenAIProvider(private val config: ModelConfig) : LLMClient {

    companion object {
        private const val TAG = "OpenAIProvider"
        private const val BASE_URL = "https://api.openai.com/v1/chat/completions"
    }

    override val providerType = LLMProviderType.OPENAI
    private val cancelled = AtomicBoolean(false)
    private var currentConnection: HttpURLConnection? = null

    override fun sendBlocking(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        tools: List<ToolDefinition>
    ): LLMResponse {
        cancelled.set(false)
        val body = buildRequestBody(messages, systemPrompt, temperature, tools, stream = false)
        val conn = openConnection()
        currentConnection = conn

        try {
            writeBody(conn, body)
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw LLMException("HTTP_$responseCode", errorBody)
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            return parseBlockingResponse(responseText)
        } finally {
            currentConnection = null
            conn.disconnect()
        }
    }

    override fun sendStreaming(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        callback: StreamingCallback,
        tools: List<ToolDefinition>
    ) {
        cancelled.set(false)
        val body = buildRequestBody(messages, systemPrompt, temperature, tools, stream = true)
        val conn = openConnection()
        currentConnection = conn

        try {
            writeBody(conn, body)
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                callback.onError("HTTP_$responseCode", errorBody)
                return
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val fullContent = StringBuilder()
            var inputTokens = 0L
            var outputTokens = 0L
            val toolCalls = mutableListOf<ToolCall>()
            val toolCallArgs = mutableMapOf<Int, StringBuilder>()
            val toolCallIds = mutableMapOf<Int, String>()
            val toolCallNames = mutableMapOf<Int, String>()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (cancelled.get()) {
                    callback.onError("CANCELLED", "Request cancelled by user")
                    return
                }

                val data = line ?: continue
                if (!data.startsWith("data: ")) continue
                val payload = data.substring(6).trim()
                if (payload == "[DONE]" || payload.isEmpty()) continue

                val chunk = JSONObject(payload)
                val choices = chunk.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                    if (delta != null) {
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) {
                            fullContent.append(content)
                            callback.onToken(content)
                        }

                        val deltaToolCalls = delta.optJSONArray("tool_calls")
                        if (deltaToolCalls != null) {
                            for (i in 0 until deltaToolCalls.length()) {
                                val tc = deltaToolCalls.getJSONObject(i)
                                val index = tc.optInt("index", 0)
                                val id = tc.optString("id", "")
                                val function = tc.optJSONObject("function")

                                if (id.isNotEmpty()) {
                                    toolCallIds[index] = id
                                }
                                if (function != null) {
                                    val name = function.optString("name", "")
                                    if (name.isNotEmpty()) {
                                        toolCallNames[index] = name
                                    }
                                    val args = function.optString("arguments", "")
                                    if (args.isNotEmpty()) {
                                        toolCallArgs.getOrPut(index) { StringBuilder() }.append(args)
                                    }
                                }
                            }
                        }
                    }
                }

                val usage = chunk.optJSONObject("usage")
                if (usage != null) {
                    inputTokens = usage.optLong("prompt_tokens", inputTokens)
                    outputTokens = usage.optLong("completion_tokens", outputTokens)
                }
            }

            for ((index, id) in toolCallIds) {
                toolCalls.add(ToolCall(
                    id = id,
                    name = toolCallNames[index] ?: "",
                    arguments = toolCallArgs[index]?.toString() ?: "{}"
                ))
            }

            val response = LLMResponse(
                content = fullContent.toString(),
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                model = config.model,
                toolCalls = toolCalls
            )
            callback.onComplete(response)
        } catch (e: Exception) {
            if (!cancelled.get()) {
                Log.e(TAG, "Streaming error", e)
                callback.onError("STREAM_ERROR", e.message ?: "Unknown streaming error")
            }
        } finally {
            currentConnection = null
            conn.disconnect()
        }
    }

    override fun cancelCurrent() {
        cancelled.set(true)
        currentConnection?.disconnect()
    }

    private fun openConnection(): HttpURLConnection {
        val url = URL(BASE_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: JSONObject) {
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        tools: List<ToolDefinition>,
        stream: Boolean
    ): JSONObject {
        val body = JSONObject()
        body.put("model", config.model)
        body.put("temperature", temperature.toDouble())
        body.put("stream", stream)
        if (stream) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        val messagesArray = JSONArray()
        if (systemPrompt.isNotBlank()) {
            val sysMsg = JSONObject()
            sysMsg.put("role", "system")
            sysMsg.put("content", systemPrompt)
            messagesArray.put(sysMsg)
        }
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            messagesArray.put(msgObj)
        }
        body.put("messages", messagesArray)

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                val toolObj = JSONObject()
                toolObj.put("type", "function")
                val funcObj = JSONObject()
                funcObj.put("name", tool.name)
                funcObj.put("description", tool.description)
                funcObj.put("parameters", JSONObject(tool.parameters))
                toolObj.put("function", funcObj)
                toolsArray.put(toolObj)
            }
            body.put("tools", toolsArray)
        }

        return body
    }

    private fun parseBlockingResponse(responseText: String): LLMResponse {
        val json = JSONObject(responseText)
        val choices = json.optJSONArray("choices")
        val message = choices?.getJSONObject(0)?.optJSONObject("message")
        val content = message?.optString("content", "") ?: ""

        val toolCalls = mutableListOf<ToolCall>()
        val tcArray = message?.optJSONArray("tool_calls")
        if (tcArray != null) {
            for (i in 0 until tcArray.length()) {
                val tc = tcArray.getJSONObject(i)
                val function = tc.optJSONObject("function")
                toolCalls.add(ToolCall(
                    id = tc.optString("id", ""),
                    name = function?.optString("name", "") ?: "",
                    arguments = function?.optString("arguments", "{}") ?: "{}"
                ))
            }
        }

        val usage = json.optJSONObject("usage")
        return LLMResponse(
            content = content,
            inputTokens = usage?.optLong("prompt_tokens", 0) ?: 0,
            outputTokens = usage?.optLong("completion_tokens", 0) ?: 0,
            model = json.optString("model", config.model),
            toolCalls = toolCalls,
            finishReason = choices?.getJSONObject(0)?.optString("finish_reason", "stop") ?: "stop"
        )
    }
}
```

- [ ] **Step 3: Create GrokProvider.kt**

Grok uses OpenAI-compatible API format with a different base URL.

Create `packages/apps/DollOSAIService/src/org/dollos/ai/llm/GrokProvider.kt`:

```kotlin
package org.dollos.ai.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class GrokProvider(private val config: ModelConfig) : LLMClient {

    companion object {
        private const val TAG = "GrokProvider"
        private const val BASE_URL = "https://api.x.ai/v1/chat/completions"
    }

    override val providerType = LLMProviderType.GROK
    private val cancelled = AtomicBoolean(false)
    private var currentConnection: HttpURLConnection? = null

    override fun sendBlocking(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        tools: List<ToolDefinition>
    ): LLMResponse {
        cancelled.set(false)
        val body = buildRequestBody(messages, systemPrompt, temperature, tools, stream = false)
        val conn = openConnection()
        currentConnection = conn

        try {
            writeBody(conn, body)
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw LLMException("HTTP_$responseCode", errorBody)
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            return parseBlockingResponse(responseText)
        } finally {
            currentConnection = null
            conn.disconnect()
        }
    }

    override fun sendStreaming(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        callback: StreamingCallback,
        tools: List<ToolDefinition>
    ) {
        cancelled.set(false)
        val body = buildRequestBody(messages, systemPrompt, temperature, tools, stream = true)
        val conn = openConnection()
        currentConnection = conn

        try {
            writeBody(conn, body)
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                callback.onError("HTTP_$responseCode", errorBody)
                return
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val fullContent = StringBuilder()
            var inputTokens = 0L
            var outputTokens = 0L
            val toolCalls = mutableListOf<ToolCall>()
            val toolCallArgs = mutableMapOf<Int, StringBuilder>()
            val toolCallIds = mutableMapOf<Int, String>()
            val toolCallNames = mutableMapOf<Int, String>()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (cancelled.get()) {
                    callback.onError("CANCELLED", "Request cancelled by user")
                    return
                }

                val data = line ?: continue
                if (!data.startsWith("data: ")) continue
                val payload = data.substring(6).trim()
                if (payload == "[DONE]" || payload.isEmpty()) continue

                val chunk = JSONObject(payload)
                val choices = chunk.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                    if (delta != null) {
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) {
                            fullContent.append(content)
                            callback.onToken(content)
                        }

                        val deltaToolCalls = delta.optJSONArray("tool_calls")
                        if (deltaToolCalls != null) {
                            for (i in 0 until deltaToolCalls.length()) {
                                val tc = deltaToolCalls.getJSONObject(i)
                                val index = tc.optInt("index", 0)
                                val id = tc.optString("id", "")
                                val function = tc.optJSONObject("function")

                                if (id.isNotEmpty()) {
                                    toolCallIds[index] = id
                                }
                                if (function != null) {
                                    val name = function.optString("name", "")
                                    if (name.isNotEmpty()) {
                                        toolCallNames[index] = name
                                    }
                                    val args = function.optString("arguments", "")
                                    if (args.isNotEmpty()) {
                                        toolCallArgs.getOrPut(index) { StringBuilder() }.append(args)
                                    }
                                }
                            }
                        }
                    }
                }

                val usage = chunk.optJSONObject("usage")
                if (usage != null) {
                    inputTokens = usage.optLong("prompt_tokens", inputTokens)
                    outputTokens = usage.optLong("completion_tokens", outputTokens)
                }
            }

            for ((index, id) in toolCallIds) {
                toolCalls.add(ToolCall(
                    id = id,
                    name = toolCallNames[index] ?: "",
                    arguments = toolCallArgs[index]?.toString() ?: "{}"
                ))
            }

            val response = LLMResponse(
                content = fullContent.toString(),
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                model = config.model,
                toolCalls = toolCalls
            )
            callback.onComplete(response)
        } catch (e: Exception) {
            if (!cancelled.get()) {
                Log.e(TAG, "Streaming error", e)
                callback.onError("STREAM_ERROR", e.message ?: "Unknown streaming error")
            }
        } finally {
            currentConnection = null
            conn.disconnect()
        }
    }

    override fun cancelCurrent() {
        cancelled.set(true)
        currentConnection?.disconnect()
    }

    private fun openConnection(): HttpURLConnection {
        val url = URL(BASE_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: JSONObject) {
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        tools: List<ToolDefinition>,
        stream: Boolean
    ): JSONObject {
        val body = JSONObject()
        body.put("model", config.model)
        body.put("temperature", temperature.toDouble())
        body.put("stream", stream)
        if (stream) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        val messagesArray = JSONArray()
        if (systemPrompt.isNotBlank()) {
            val sysMsg = JSONObject()
            sysMsg.put("role", "system")
            sysMsg.put("content", systemPrompt)
            messagesArray.put(sysMsg)
        }
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            messagesArray.put(msgObj)
        }
        body.put("messages", messagesArray)

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                val toolObj = JSONObject()
                toolObj.put("type", "function")
                val funcObj = JSONObject()
                funcObj.put("name", tool.name)
                funcObj.put("description", tool.description)
                funcObj.put("parameters", JSONObject(tool.parameters))
                toolObj.put("function", funcObj)
                toolsArray.put(toolObj)
            }
            body.put("tools", toolsArray)
        }

        return body
    }

    private fun parseBlockingResponse(responseText: String): LLMResponse {
        val json = JSONObject(responseText)
        val choices = json.optJSONArray("choices")
        val message = choices?.getJSONObject(0)?.optJSONObject("message")
        val content = message?.optString("content", "") ?: ""

        val toolCalls = mutableListOf<ToolCall>()
        val tcArray = message?.optJSONArray("tool_calls")
        if (tcArray != null) {
            for (i in 0 until tcArray.length()) {
                val tc = tcArray.getJSONObject(i)
                val function = tc.optJSONObject("function")
                toolCalls.add(ToolCall(
                    id = tc.optString("id", ""),
                    name = function?.optString("name", "") ?: "",
                    arguments = function?.optString("arguments", "{}") ?: "{}"
                ))
            }
        }

        val usage = json.optJSONObject("usage")
        return LLMResponse(
            content = content,
            inputTokens = usage?.optLong("prompt_tokens", 0) ?: 0,
            outputTokens = usage?.optLong("completion_tokens", 0) ?: 0,
            model = json.optString("model", config.model),
            toolCalls = toolCalls,
            finishReason = choices?.getJSONObject(0)?.optString("finish_reason", "stop") ?: "stop"
        )
    }
}
```

- [ ] **Step 4: Create CustomProvider.kt**

Custom provider uses OpenAI-compatible format with user-configurable base URL.

Create `packages/apps/DollOSAIService/src/org/dollos/ai/llm/CustomProvider.kt`:

```kotlin
package org.dollos.ai.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class CustomProvider(private val config: ModelConfig) : LLMClient {

    companion object {
        private const val TAG = "CustomProvider"
    }

    override val providerType = LLMProviderType.CUSTOM
    private val cancelled = AtomicBoolean(false)
    private var currentConnection: HttpURLConnection? = null

    private val effectiveBaseUrl: String
        get() = if (config.baseUrl.isNotBlank()) {
            config.baseUrl.trimEnd('/') + "/chat/completions"
        } else {
            throw LLMException("CONFIG_ERROR", "Custom provider requires a base URL")
        }

    override fun sendBlocking(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        tools: List<ToolDefinition>
    ): LLMResponse {
        cancelled.set(false)
        val body = buildRequestBody(messages, systemPrompt, temperature, tools, stream = false)
        val conn = openConnection()
        currentConnection = conn

        try {
            writeBody(conn, body)
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw LLMException("HTTP_$responseCode", errorBody)
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            return parseBlockingResponse(responseText)
        } finally {
            currentConnection = null
            conn.disconnect()
        }
    }

    override fun sendStreaming(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        callback: StreamingCallback,
        tools: List<ToolDefinition>
    ) {
        cancelled.set(false)
        val body = buildRequestBody(messages, systemPrompt, temperature, tools, stream = true)
        val conn = openConnection()
        currentConnection = conn

        try {
            writeBody(conn, body)
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                callback.onError("HTTP_$responseCode", errorBody)
                return
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val fullContent = StringBuilder()
            var inputTokens = 0L
            var outputTokens = 0L

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (cancelled.get()) {
                    callback.onError("CANCELLED", "Request cancelled by user")
                    return
                }

                val data = line ?: continue
                if (!data.startsWith("data: ")) continue
                val payload = data.substring(6).trim()
                if (payload == "[DONE]" || payload.isEmpty()) continue

                val chunk = JSONObject(payload)
                val choices = chunk.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                    if (delta != null) {
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) {
                            fullContent.append(content)
                            callback.onToken(content)
                        }
                    }
                }

                val usage = chunk.optJSONObject("usage")
                if (usage != null) {
                    inputTokens = usage.optLong("prompt_tokens", inputTokens)
                    outputTokens = usage.optLong("completion_tokens", outputTokens)
                }
            }

            val response = LLMResponse(
                content = fullContent.toString(),
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                model = config.model
            )
            callback.onComplete(response)
        } catch (e: Exception) {
            if (!cancelled.get()) {
                Log.e(TAG, "Streaming error", e)
                callback.onError("STREAM_ERROR", e.message ?: "Unknown streaming error")
            }
        } finally {
            currentConnection = null
            conn.disconnect()
        }
    }

    override fun cancelCurrent() {
        cancelled.set(true)
        currentConnection?.disconnect()
    }

    private fun openConnection(): HttpURLConnection {
        val url = URL(effectiveBaseUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        if (config.apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: JSONObject) {
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String,
        temperature: Float,
        tools: List<ToolDefinition>,
        stream: Boolean
    ): JSONObject {
        val body = JSONObject()
        body.put("model", config.model)
        body.put("temperature", temperature.toDouble())
        body.put("stream", stream)

        val messagesArray = JSONArray()
        if (systemPrompt.isNotBlank()) {
            val sysMsg = JSONObject()
            sysMsg.put("role", "system")
            sysMsg.put("content", systemPrompt)
            messagesArray.put(sysMsg)
        }
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            messagesArray.put(msgObj)
        }
        body.put("messages", messagesArray)

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                val toolObj = JSONObject()
                toolObj.put("type", "function")
                val funcObj = JSONObject()
                funcObj.put("name", tool.name)
                funcObj.put("description", tool.description)
                funcObj.put("parameters", JSONObject(tool.parameters))
                toolObj.put("function", funcObj)
                toolsArray.put(toolObj)
            }
            body.put("tools", toolsArray)
        }

        return body
    }

    private fun parseBlockingResponse(responseText: String): LLMResponse {
        val json = JSONObject(responseText)
        val choices = json.optJSONArray("choices")
        val message = choices?.getJSONObject(0)?.optJSONObject("message")
        val content = message?.optString("content", "") ?: ""

        val usage = json.optJSONObject("usage")
        return LLMResponse(
            content = content,
            inputTokens = usage?.optLong("prompt_tokens", 0) ?: 0,
            outputTokens = usage?.optLong("completion_tokens", 0) ?: 0,
            model = json.optString("model", config.model)
        )
    }
}
```

- [ ] **Step 5: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/llm/
git commit -m "feat: add Claude, OpenAI, Grok, and Custom LLM provider implementations"
```

---

## Task 6: Personality System

**Goal:** Implement the 5-field personality system (backstory, response directive, dynamism, address, language preference) with SharedPreferences persistence and system prompt construction.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/personality/PersonalityManager.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/personality/SystemPromptBuilder.kt`

- [ ] **Step 1: Create PersonalityManager.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/personality/PersonalityManager.kt`:

```kotlin
package org.dollos.ai.personality

import android.content.SharedPreferences
import android.util.Log

class PersonalityManager(private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "PersonalityManager"
        private const val KEY_BACKSTORY = "personality_backstory"
        private const val KEY_RESPONSE_DIRECTIVE = "personality_response_directive"
        private const val KEY_DYNAMISM = "personality_dynamism"
        private const val KEY_ADDRESS = "personality_address"
        private const val KEY_LANGUAGE_PREFERENCE = "personality_language_preference"

        const val BACKSTORY_MAX_LENGTH = 2500
        const val RESPONSE_DIRECTIVE_MAX_LENGTH = 150
        const val ADDRESS_MAX_LENGTH = 50
    }

    var backstory: String
        get() = prefs.getString(KEY_BACKSTORY, "") ?: ""
        set(value) {
            val trimmed = value.take(BACKSTORY_MAX_LENGTH)
            prefs.edit().putString(KEY_BACKSTORY, trimmed).apply()
            Log.i(TAG, "Backstory updated (${trimmed.length} chars)")
        }

    var responseDirective: String
        get() = prefs.getString(KEY_RESPONSE_DIRECTIVE, "") ?: ""
        set(value) {
            val trimmed = value.take(RESPONSE_DIRECTIVE_MAX_LENGTH)
            prefs.edit().putString(KEY_RESPONSE_DIRECTIVE, trimmed).apply()
            Log.i(TAG, "Response directive updated (${trimmed.length} chars)")
        }

    var dynamism: Float
        get() = prefs.getFloat(KEY_DYNAMISM, 0.5f)
        set(value) {
            val clamped = value.coerceIn(0.0f, 1.0f)
            prefs.edit().putFloat(KEY_DYNAMISM, clamped).apply()
            Log.i(TAG, "Dynamism updated: $clamped")
        }

    var address: String
        get() = prefs.getString(KEY_ADDRESS, "") ?: ""
        set(value) {
            val trimmed = value.take(ADDRESS_MAX_LENGTH)
            prefs.edit().putString(KEY_ADDRESS, trimmed).apply()
            Log.i(TAG, "Address updated: $trimmed")
        }

    var languagePreference: String
        get() = prefs.getString(KEY_LANGUAGE_PREFERENCE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE_PREFERENCE, value).apply()
            Log.i(TAG, "Language preference updated: $value")
        }

    fun isConfigured(): Boolean {
        return backstory.isNotBlank() || responseDirective.isNotBlank()
    }
}
```

- [ ] **Step 2: Create SystemPromptBuilder.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/personality/SystemPromptBuilder.kt`:

```kotlin
package org.dollos.ai.personality

class SystemPromptBuilder(private val personalityManager: PersonalityManager) {

    fun build(): String {
        val sections = mutableListOf<String>()

        val directive = personalityManager.responseDirective
        if (directive.isNotBlank()) {
            sections.add("[Response Directive]\n$directive")
        }

        val language = personalityManager.languagePreference
        if (language.isNotBlank()) {
            sections.add("[Language Preference]\nRespond in $language.")
        }

        val address = personalityManager.address
        if (address.isNotBlank()) {
            sections.add("[How to Address User]\nAddress the user as \"$address\".")
        }

        val backstory = personalityManager.backstory
        if (backstory.isNotBlank()) {
            sections.add("[Backstory]\n$backstory")
        }

        val dynamism = personalityManager.dynamism
        val creativityNote = when {
            dynamism < 0.3f -> "Be consistent and predictable in your responses. Avoid creative embellishments."
            dynamism < 0.7f -> "Balance consistency with occasional creative expression."
            else -> "Be creative and expressive. Vary your responses and show personality."
        }
        sections.add("[Creativity Level]\n$creativityNote")

        return sections.joinToString("\n\n")
    }

    fun buildWithMemory(tier1Memory: String, tier2Memory: String, searchResults: String): String {
        val sections = mutableListOf<String>()

        sections.add(build())

        if (tier1Memory.isNotBlank()) {
            sections.add("[Core Memories]\n$tier1Memory")
        }

        if (tier2Memory.isNotBlank()) {
            sections.add("[Recent Memories]\n$tier2Memory")
        }

        if (searchResults.isNotBlank()) {
            sections.add("[Relevant Memories]\n$searchResults")
        }

        return sections.joinToString("\n\n")
    }
}
```

- [ ] **Step 3: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/personality/
git commit -m "feat: add PersonalityManager with 5-field config and SystemPromptBuilder"
```

---

## Task 7: Usage Tracking and Budget Controls

**Goal:** Implement token usage tracking with per-call recording, and budget controls with warning threshold and hard limit (daily/monthly periods). Budget violations trigger Android system notifications and can block API calls.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/usage/UsageTracker.kt`
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/usage/BudgetManager.kt`

- [ ] **Step 1: Create UsageTracker.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/usage/UsageTracker.kt`:

```kotlin
package org.dollos.ai.usage

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class UsageRecord(
    val timestamp: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val model: String,
    val provider: String,
    val function: String,
    val isForeground: Boolean
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("inputTokens", inputTokens)
            put("outputTokens", outputTokens)
            put("model", model)
            put("provider", provider)
            put("function", function)
            put("isForeground", isForeground)
        }
    }

    val totalTokens: Long get() = inputTokens + outputTokens
}

class UsageTracker(private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "UsageTracker"
        private const val KEY_USAGE_RECORDS = "usage_records"
        private const val MAX_RECORDS = 10000
    }

    private val records = mutableListOf<UsageRecord>()
    private val lock = Any()

    init {
        loadRecords()
    }

    fun record(
        inputTokens: Long,
        outputTokens: Long,
        model: String,
        provider: String,
        function: String,
        isForeground: Boolean
    ) {
        synchronized(lock) {
            val record = UsageRecord(
                timestamp = System.currentTimeMillis(),
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                model = model,
                provider = provider,
                function = function,
                isForeground = isForeground
            )
            records.add(record)

            if (records.size > MAX_RECORDS) {
                records.removeAt(0)
            }

            saveRecords()
            Log.i(TAG, "Usage recorded: ${record.totalTokens} tokens ($provider/$model, $function)")
        }
    }

    fun getTotalTokensForPeriod(period: String): Long {
        synchronized(lock) {
            val cutoff = getPeriodCutoff(period)
            return records.filter { it.timestamp >= cutoff }.sumOf { it.totalTokens }
        }
    }

    fun getStats(): JSONObject {
        synchronized(lock) {
            val dailyCutoff = getPeriodCutoff("daily")
            val monthlyCutoff = getPeriodCutoff("monthly")

            val dailyRecords = records.filter { it.timestamp >= dailyCutoff }
            val monthlyRecords = records.filter { it.timestamp >= monthlyCutoff }

            return JSONObject().apply {
                put("daily", JSONObject().apply {
                    put("totalTokens", dailyRecords.sumOf { it.totalTokens })
                    put("inputTokens", dailyRecords.sumOf { it.inputTokens })
                    put("outputTokens", dailyRecords.sumOf { it.outputTokens })
                    put("callCount", dailyRecords.size)
                })
                put("monthly", JSONObject().apply {
                    put("totalTokens", monthlyRecords.sumOf { it.totalTokens })
                    put("inputTokens", monthlyRecords.sumOf { it.inputTokens })
                    put("outputTokens", monthlyRecords.sumOf { it.outputTokens })
                    put("callCount", monthlyRecords.size)
                })
                put("allTime", JSONObject().apply {
                    put("totalTokens", records.sumOf { it.totalTokens })
                    put("inputTokens", records.sumOf { it.inputTokens })
                    put("outputTokens", records.sumOf { it.outputTokens })
                    put("callCount", records.size)
                })
            }
        }
    }

    private fun getPeriodCutoff(period: String): Long {
        val cal = Calendar.getInstance()
        when (period) {
            "daily" -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            "monthly" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
        }
        return cal.timeInMillis
    }

    private fun loadRecords() {
        val json = prefs.getString(KEY_USAGE_RECORDS, "[]") ?: "[]"
        val arr = JSONArray(json)
        records.clear()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            records.add(UsageRecord(
                timestamp = obj.optLong("timestamp", 0),
                inputTokens = obj.optLong("inputTokens", 0),
                outputTokens = obj.optLong("outputTokens", 0),
                model = obj.optString("model", ""),
                provider = obj.optString("provider", ""),
                function = obj.optString("function", ""),
                isForeground = obj.optBoolean("isForeground", true)
            ))
        }
        Log.i(TAG, "Loaded ${records.size} usage records")
    }

    private fun saveRecords() {
        val arr = JSONArray()
        for (record in records) {
            arr.put(record.toJson())
        }
        prefs.edit().putString(KEY_USAGE_RECORDS, arr.toString()).apply()
    }
}
```

- [ ] **Step 2: Create BudgetManager.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/usage/BudgetManager.kt`:

```kotlin
package org.dollos.ai.usage

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import org.dollos.ai.DollOSAIApp

class BudgetManager(
    private val prefs: SharedPreferences,
    private val usageTracker: UsageTracker,
    private val context: Context
) {

    companion object {
        private const val TAG = "BudgetManager"
        private const val KEY_WARNING_TOKENS = "budget_warning_tokens"
        private const val KEY_WARNING_PERIOD = "budget_warning_period"
        private const val KEY_HARD_LIMIT_TOKENS = "budget_hard_limit_tokens"
        private const val KEY_HARD_LIMIT_PERIOD = "budget_hard_limit_period"
        private const val KEY_WARNING_NOTIFIED = "budget_warning_notified"
        private const val NOTIFICATION_ID_WARNING = 1001
        private const val NOTIFICATION_ID_HARD_LIMIT = 1002
    }

    var warningTokens: Long
        get() = prefs.getLong(KEY_WARNING_TOKENS, 0)
        private set(value) { prefs.edit().putLong(KEY_WARNING_TOKENS, value).apply() }

    var warningPeriod: String
        get() = prefs.getString(KEY_WARNING_PERIOD, "daily") ?: "daily"
        private set(value) { prefs.edit().putString(KEY_WARNING_PERIOD, value).apply() }

    var hardLimitTokens: Long
        get() = prefs.getLong(KEY_HARD_LIMIT_TOKENS, 0)
        private set(value) { prefs.edit().putLong(KEY_HARD_LIMIT_TOKENS, value).apply() }

    var hardLimitPeriod: String
        get() = prefs.getString(KEY_HARD_LIMIT_PERIOD, "daily") ?: "daily"
        private set(value) { prefs.edit().putString(KEY_HARD_LIMIT_PERIOD, value).apply() }

    fun setWarningThreshold(tokens: Long, period: String) {
        warningTokens = tokens
        warningPeriod = period
        prefs.edit().putBoolean(KEY_WARNING_NOTIFIED, false).apply()
        Log.i(TAG, "Warning threshold set: $tokens tokens per $period")
    }

    fun setHardLimit(tokens: Long, period: String) {
        hardLimitTokens = tokens
        hardLimitPeriod = period
        Log.i(TAG, "Hard limit set: $tokens tokens per $period")
    }

    fun isHardLimitReached(): Boolean {
        if (hardLimitTokens <= 0) return false
        val used = usageTracker.getTotalTokensForPeriod(hardLimitPeriod)
        return used >= hardLimitTokens
    }

    fun checkBudgetAfterCall() {
        checkWarning()
        checkHardLimit()
    }

    private fun checkWarning() {
        if (warningTokens <= 0) return

        val used = usageTracker.getTotalTokensForPeriod(warningPeriod)
        val alreadyNotified = prefs.getBoolean(KEY_WARNING_NOTIFIED, false)

        if (used >= warningTokens && !alreadyNotified) {
            prefs.edit().putBoolean(KEY_WARNING_NOTIFIED, true).apply()
            sendNotification(
                NOTIFICATION_ID_WARNING,
                "AI Usage Warning",
                "You have used $used tokens this $warningPeriod (threshold: $warningTokens)."
            )
            Log.w(TAG, "Warning threshold reached: $used / $warningTokens ($warningPeriod)")
        }
    }

    private fun checkHardLimit() {
        if (hardLimitTokens <= 0) return

        val used = usageTracker.getTotalTokensForPeriod(hardLimitPeriod)
        if (used >= hardLimitTokens) {
            sendNotification(
                NOTIFICATION_ID_HARD_LIMIT,
                "AI Usage Limit Reached",
                "All AI functions stopped. Used $used tokens this $hardLimitPeriod (limit: $hardLimitTokens)."
            )
            Log.w(TAG, "Hard limit reached: $used / $hardLimitTokens ($hardLimitPeriod)")
        }
    }

    private fun sendNotification(id: Int, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, DollOSAIApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notification)
    }
}
```

- [ ] **Step 3: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/usage/
git commit -m "feat: add UsageTracker and BudgetManager with warning/hard limit controls"
```

---

## Task 8: AIDL Service Implementation

**Goal:** Implement the full `IDollOSAIService.Stub` with all AIDL methods. This ties together the LLM client, personality manager, usage tracker, budget manager, and DollOSService delegation. Includes pauseAll() with 3s synchronous blocking, callback management, and error notification.

**Files:**
- Create: `packages/apps/DollOSAIService/src/org/dollos/ai/DollOSAIServiceImpl.kt`

- [ ] **Step 1: Create DollOSAIServiceImpl.kt**

Create `packages/apps/DollOSAIService/src/org/dollos/ai/DollOSAIServiceImpl.kt`:

```kotlin
package org.dollos.ai

import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.util.Log
import androidx.core.app.NotificationCompat
import org.dollos.ai.llm.ClaudeProvider
import org.dollos.ai.llm.CustomProvider
import org.dollos.ai.llm.GrokProvider
import org.dollos.ai.llm.LLMClient
import org.dollos.ai.llm.LLMException
import org.dollos.ai.llm.LLMMessage
import org.dollos.ai.llm.LLMProviderType
import org.dollos.ai.llm.LLMResponse
import org.dollos.ai.llm.ModelConfig
import org.dollos.ai.llm.OpenAIProvider
import org.dollos.ai.llm.StreamingCallback
import org.dollos.ai.llm.ToolCall
import org.dollos.ai.llm.ToolDefinition
import org.dollos.ai.personality.PersonalityManager
import org.dollos.ai.personality.SystemPromptBuilder
import org.dollos.ai.usage.BudgetManager
import org.dollos.ai.usage.UsageTracker
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DollOSAIServiceImpl(private val service: DollOSAIService) : IDollOSAIService.Stub() {

    companion object {
        private const val TAG = "DollOSAIServiceImpl"
        private const val KEY_FG_PROVIDER = "fg_provider"
        private const val KEY_FG_API_KEY = "fg_api_key"
        private const val KEY_FG_MODEL = "fg_model"
        private const val KEY_FG_BASE_URL = "fg_base_url"
        private const val KEY_BG_PROVIDER = "bg_provider"
        private const val KEY_BG_API_KEY = "bg_api_key"
        private const val KEY_BG_MODEL = "bg_model"
        private const val KEY_BG_BASE_URL = "bg_base_url"
        private const val PAUSE_TIMEOUT_MS = 3000L
    }

    private val callbacks = RemoteCallbackList<IDollOSAICallback>()
    private val executor = Executors.newCachedThreadPool()
    private val personalityManager = PersonalityManager(DollOSAIApp.prefs)
    private val systemPromptBuilder = SystemPromptBuilder(personalityManager)
    private val usageTracker = UsageTracker(DollOSAIApp.prefs)
    private val budgetManager = BudgetManager(DollOSAIApp.prefs, usageTracker, DollOSAIApp.instance)

    private var foregroundClient: LLMClient? = null
    private var backgroundClient: LLMClient? = null
    private val paused = AtomicBoolean(false)
    private val activeTasks = ConcurrentHashMap<String, TaskInfo>()

    data class TaskInfo(
        val id: String,
        val name: String,
        val description: String,
        val startTime: Long,
        var tokenUsage: Long = 0,
        var status: String = "RUNNING"
    )

    init {
        initForegroundClient()
        initBackgroundClient()
    }

    // --- Callback registration ---

    override fun registerCallback(callback: IDollOSAICallback?) {
        if (callback != null) {
            callbacks.register(callback)
            Log.i(TAG, "Callback registered")
        }
    }

    override fun unregisterCallback(callback: IDollOSAICallback?) {
        if (callback != null) {
            callbacks.unregister(callback)
            Log.i(TAG, "Callback unregistered")
        }
    }

    // --- Conversation ---

    override fun sendMessage(message: String?) {
        if (message.isNullOrBlank()) return

        if (paused.get()) {
            broadcastError("PAUSED", "AI is paused")
            return
        }

        if (budgetManager.isHardLimitReached()) {
            broadcastError("BUDGET_LIMIT", "Token budget limit reached")
            return
        }

        val client = foregroundClient
        if (client == null) {
            broadcastError("NOT_CONFIGURED", "No foreground model configured")
            return
        }

        val taskId = UUID.randomUUID().toString()
        val task = TaskInfo(
            id = taskId,
            name = "Conversation",
            description = "Processing: ${message.take(50)}",
            startTime = System.currentTimeMillis()
        )
        activeTasks[taskId] = task

        executor.submit {
            try {
                val systemPrompt = systemPromptBuilder.build()
                val temperature = client.providerType.clampTemperature(personalityManager.dynamism)
                val messages = listOf(LLMMessage(role = "user", content = message))

                val tools = loadToolDefinitions()

                client.sendStreaming(messages, systemPrompt, temperature, object : StreamingCallback {
                    override fun onToken(token: String) {
                        if (paused.get()) return
                        broadcastToken(token)
                    }

                    override fun onComplete(response: LLMResponse) {
                        activeTasks.remove(taskId)

                        usageTracker.record(
                            inputTokens = response.inputTokens,
                            outputTokens = response.outputTokens,
                            model = response.model,
                            provider = client.providerType.name,
                            function = "conversation",
                            isForeground = true
                        )
                        budgetManager.checkBudgetAfterCall()

                        if (response.toolCalls.isNotEmpty()) {
                            handleToolCalls(response.toolCalls)
                        }

                        broadcastComplete(response.content)
                    }

                    override fun onError(errorCode: String, message: String) {
                        activeTasks.remove(taskId)
                        sendErrorNotification(errorCode, message)
                        broadcastError(errorCode, message)
                    }
                }, tools)
            } catch (e: LLMException) {
                activeTasks.remove(taskId)
                sendErrorNotification(e.errorCode, e.message ?: "Unknown error")
                broadcastError(e.errorCode, e.message ?: "Unknown error")
            } catch (e: Exception) {
                activeTasks.remove(taskId)
                sendErrorNotification("UNKNOWN", e.message ?: "Unknown error")
                broadcastError("UNKNOWN", e.message ?: "Unknown error")
            }
        }
    }

    override fun stopGeneration() {
        foregroundClient?.cancelCurrent()
        backgroundClient?.cancelCurrent()
        Log.i(TAG, "Generation stopped")
    }

    override fun pauseAll(): Boolean {
        Log.i(TAG, "pauseAll() called")
        paused.set(true)

        foregroundClient?.cancelCurrent()
        backgroundClient?.cancelCurrent()

        val latch = CountDownLatch(1)

        executor.submit {
            for (task in activeTasks.values) {
                task.status = "PAUSED"
            }
            latch.countDown()
        }

        val completed = latch.await(PAUSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "pauseAll() completed: $completed, active tasks: ${activeTasks.size}")
        return completed
    }

    override fun resumeAll() {
        Log.i(TAG, "resumeAll() called")
        paused.set(false)
        for (task in activeTasks.values) {
            task.status = "RUNNING"
        }
    }

    // --- Personality ---

    override fun setBackstory(backstory: String?) {
        personalityManager.backstory = backstory ?: ""
    }

    override fun setResponseDirective(directive: String?) {
        personalityManager.responseDirective = directive ?: ""
    }

    override fun setDynamism(value: Float) {
        personalityManager.dynamism = value
    }

    override fun setAddress(address: String?) {
        personalityManager.address = address ?: ""
    }

    override fun setLanguagePreference(language: String?) {
        personalityManager.languagePreference = language ?: ""
    }

    override fun getBackstory(): String = personalityManager.backstory

    override fun getResponseDirective(): String = personalityManager.responseDirective

    override fun getDynamism(): Float = personalityManager.dynamism

    override fun getAddress(): String = personalityManager.address

    override fun getLanguagePreference(): String = personalityManager.languagePreference

    // --- API Configuration ---

    override fun setForegroundModel(provider: String?, apiKey: String?, model: String?) {
        if (provider == null || apiKey == null || model == null) return
        DollOSAIApp.prefs.edit()
            .putString(KEY_FG_PROVIDER, provider)
            .putString(KEY_FG_API_KEY, apiKey)
            .putString(KEY_FG_MODEL, model)
            .apply()
        initForegroundClient()
        Log.i(TAG, "Foreground model set: $provider / $model")
    }

    override fun setBackgroundModel(provider: String?, apiKey: String?, model: String?) {
        if (provider == null || apiKey == null || model == null) return
        DollOSAIApp.prefs.edit()
            .putString(KEY_BG_PROVIDER, provider)
            .putString(KEY_BG_API_KEY, apiKey)
            .putString(KEY_BG_MODEL, model)
            .apply()
        initBackgroundClient()
        Log.i(TAG, "Background model set: $provider / $model")
    }

    // --- Embedding Configuration ---

    override fun setEmbeddingSource(source: String?) {
        DollOSAIApp.prefs.edit().putString("embedding_source", source ?: "local").apply()
        Log.i(TAG, "Embedding source set: $source")
    }

    override fun getEmbeddingSource(): String {
        return DollOSAIApp.prefs.getString("embedding_source", "local") ?: "local"
    }

    // --- Memory (stubs for Plan B) ---

    override fun searchMemory(query: String?): String {
        // Plan B will implement full memory search
        return "[]"
    }

    override fun exportMemory(fd: ParcelFileDescriptor?) {
        // Plan B will implement memory export
        Log.i(TAG, "exportMemory called (not yet implemented, see Plan B)")
    }

    override fun importMemory(fd: ParcelFileDescriptor?) {
        // Plan B will implement memory import
        Log.i(TAG, "importMemory called (not yet implemented, see Plan B)")
    }

    override fun confirmMemoryWrite(approved: Boolean) {
        // Plan B will implement memory confirmation
        Log.i(TAG, "confirmMemoryWrite called: $approved (not yet implemented, see Plan B)")
    }

    // --- Usage ---

    override fun getUsageStats(): String {
        return usageTracker.getStats().toString()
    }

    override fun setWarningThreshold(tokens: Long, period: String?) {
        budgetManager.setWarningThreshold(tokens, period ?: "daily")
    }

    override fun setHardLimit(tokens: Long, period: String?) {
        budgetManager.setHardLimit(tokens, period ?: "daily")
    }

    // --- Task Manager ---

    override fun getActiveTasks(): String {
        val arr = JSONArray()
        for (task in activeTasks.values) {
            arr.put(JSONObject().apply {
                put("id", task.id)
                put("name", task.name)
                put("description", task.description)
                put("startTime", task.startTime)
                put("tokenUsage", task.tokenUsage)
                put("estimatedCost", 0.0)
                put("conversationContext", "")
                put("status", task.status)
            })
        }
        return arr.toString()
    }

    override fun cancelTask(taskId: String?) {
        if (taskId == null) return
        activeTasks.remove(taskId)
        Log.i(TAG, "Task cancelled: $taskId")
    }

    override fun confirmAction(actionId: String?, approved: Boolean) {
        if (actionId == null) return
        confirmAction(actionId, approved)
    }

    // --- Internal helpers ---

    private fun initForegroundClient() {
        val provider = DollOSAIApp.prefs.getString(KEY_FG_PROVIDER, null) ?: return
        val apiKey = DollOSAIApp.prefs.getString(KEY_FG_API_KEY, null) ?: return
        val model = DollOSAIApp.prefs.getString(KEY_FG_MODEL, null) ?: return
        val baseUrl = DollOSAIApp.prefs.getString(KEY_FG_BASE_URL, "") ?: ""

        foregroundClient = createClient(provider, apiKey, model, baseUrl)
        Log.i(TAG, "Foreground client initialized: $provider / $model")
    }

    private fun initBackgroundClient() {
        val provider = DollOSAIApp.prefs.getString(KEY_BG_PROVIDER, null) ?: return
        val apiKey = DollOSAIApp.prefs.getString(KEY_BG_API_KEY, null) ?: return
        val model = DollOSAIApp.prefs.getString(KEY_BG_MODEL, null) ?: return
        val baseUrl = DollOSAIApp.prefs.getString(KEY_BG_BASE_URL, "") ?: ""

        backgroundClient = createClient(provider, apiKey, model, baseUrl)
        Log.i(TAG, "Background client initialized: $provider / $model")
    }

    private fun createClient(provider: String, apiKey: String, model: String, baseUrl: String): LLMClient {
        val providerType = LLMProviderType.fromString(provider)
        val config = ModelConfig(
            providerType = providerType,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl
        )
        return when (providerType) {
            LLMProviderType.CLAUDE -> ClaudeProvider(config)
            LLMProviderType.OPENAI -> OpenAIProvider(config)
            LLMProviderType.GROK -> GrokProvider(config)
            LLMProviderType.CUSTOM -> CustomProvider(config)
        }
    }

    private fun loadToolDefinitions(): List<ToolDefinition> {
        val dollOSService = service.dollOSService ?: return emptyList()
        try {
            val actionsJson = dollOSService.availableActions
            if (actionsJson.isNullOrBlank()) return emptyList()

            val arr = JSONArray(actionsJson)
            val tools = mutableListOf<ToolDefinition>()
            for (i in 0 until arr.length()) {
                val action = arr.getJSONObject(i)
                tools.add(ToolDefinition(
                    name = action.optString("id", ""),
                    description = action.optString("description", ""),
                    parameters = """{"type":"object","properties":{},"required":[]}"""
                ))
            }
            return tools
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tool definitions from DollOSService", e)
            return emptyList()
        }
    }

    // Pending confirmations: actionId -> PendingAction
    private data class PendingAction(
        val toolCall: ToolCall,
        val timeoutRunnable: Runnable
    )
    private val pendingConfirmations = ConcurrentHashMap<String, PendingAction>()
    private val confirmTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val CONFIRM_TIMEOUT_MS = 60_000L

    private fun handleToolCalls(toolCalls: List<ToolCall>) {
        val dollOSService = service.dollOSService
        if (dollOSService == null) {
            Log.e(TAG, "Cannot execute tool calls: DollOSService not connected")
            return
        }

        // Parse available actions to check confirmRequired per action
        val confirmMap = mutableMapOf<String, Boolean>()
        try {
            val actionsJson = dollOSService.availableActions
            if (!actionsJson.isNullOrBlank()) {
                val arr = JSONArray(actionsJson)
                for (i in 0 until arr.length()) {
                    val action = arr.getJSONObject(i)
                    confirmMap[action.optString("id", "")] = action.optBoolean("confirmRequired", true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse available actions for confirm check", e)
        }

        for (toolCall in toolCalls) {
            val needsConfirm = confirmMap.getOrDefault(toolCall.name, true)

            if (needsConfirm) {
                // Add to pending confirmations, broadcast confirm request, wait for user
                val timeoutRunnable = Runnable {
                    val removed = pendingConfirmations.remove(toolCall.id)
                    if (removed != null) {
                        Log.i(TAG, "Confirmation timed out for action: ${toolCall.name}")
                        broadcastActionExecuted(toolCall.id, false, "Confirmation timed out (60s)")
                    }
                }
                pendingConfirmations[toolCall.id] = PendingAction(toolCall, timeoutRunnable)
                confirmTimeoutHandler.postDelayed(timeoutRunnable, CONFIRM_TIMEOUT_MS)
                broadcastActionConfirm(toolCall.id, toolCall.name, "Execute ${toolCall.name}?")
                Log.i(TAG, "Awaiting confirmation for action: ${toolCall.name} (id=${toolCall.id})")
            } else {
                // Execute immediately -- no confirmation needed
                executor.submit {
                    executeToolCall(dollOSService, toolCall)
                }
            }
        }
    }

    fun confirmAction(actionId: String, approved: Boolean) {
        val pending = pendingConfirmations.remove(actionId)
        if (pending == null) {
            Log.w(TAG, "confirmAction called for unknown or expired actionId: $actionId")
            return
        }
        confirmTimeoutHandler.removeCallbacks(pending.timeoutRunnable)

        if (approved) {
            val dollOSService = service.dollOSService
            if (dollOSService == null) {
                broadcastActionExecuted(actionId, false, "DollOSService not connected")
                return
            }
            executor.submit {
                executeToolCall(dollOSService, pending.toolCall)
            }
        } else {
            broadcastActionExecuted(actionId, false, "Action denied by user")
            Log.i(TAG, "Action denied by user: ${pending.toolCall.name}")
        }
    }

    private fun executeToolCall(dollOSService: IDollOSService, toolCall: ToolCall) {
        try {
            val resultJson = dollOSService.executeSystemAction(toolCall.name, toolCall.arguments)
            val result = JSONObject(resultJson)
            val success = result.optBoolean("success", false)
            val message = result.optString("message", "")

            broadcastActionExecuted(toolCall.id, success, message)
            Log.i(TAG, "Tool call executed: ${toolCall.name} -> success=$success")
        } catch (e: Exception) {
            Log.e(TAG, "Tool call failed: ${toolCall.name}", e)
            broadcastActionExecuted(toolCall.id, false, e.message ?: "Execution failed")
        }
    }

    private fun broadcastToken(token: String) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).onToken(token)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast token to callback $i", e)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun broadcastComplete(fullResponse: String) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).onResponseComplete(fullResponse)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast complete to callback $i", e)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun broadcastError(errorCode: String, message: String) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).onResponseError(errorCode, message)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast error to callback $i", e)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun broadcastActionConfirm(actionId: String, actionName: String, description: String) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).onActionConfirmRequired(actionId, actionName, description)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast action confirm to callback $i", e)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun broadcastActionExecuted(actionId: String, success: Boolean, resultMessage: String) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).onActionExecuted(actionId, success, resultMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast action result to callback $i", e)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun sendErrorNotification(errorCode: String, message: String) {
        val nm = DollOSAIApp.instance.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(DollOSAIApp.instance, DollOSAIApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("AI Error: $errorCode")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(errorCode.hashCode(), notification)
    }
}
```

- [ ] **Step 2: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSAIService
git add src/org/dollos/ai/DollOSAIServiceImpl.kt
git commit -m "feat: add full DollOSAIServiceImpl with LLM, personality, usage, and task management"
```

---

## Task 9: OOBE Migration -- SetupWizard Binds to DollOSAIService

**Goal:** Update DollOSSetupWizard to bind to DollOSAIService for API key and personality pages, while keeping DollOSService binding for GMS page. Update ApiKeyPage to call `setForegroundModel` on DollOSAIService. Update PersonalityPage to use the full 5-field personality system.

**Files:**
- Modify: `packages/apps/DollOSSetupWizard/Android.bp`
- Modify: `packages/apps/DollOSSetupWizard/src/org/dollos/setup/SetupWizardActivity.kt`
- Modify: `packages/apps/DollOSSetupWizard/src/org/dollos/setup/ApiKeyPage.kt`
- Modify: `packages/apps/DollOSSetupWizard/src/org/dollos/setup/PersonalityPage.kt`
- Modify: `packages/apps/DollOSSetupWizard/res/layout/page_personality.xml`

- [ ] **Step 1: Update Android.bp to add dollos-ai-aidl dependency**

In `packages/apps/DollOSSetupWizard/Android.bp`, add `"dollos-ai-aidl"` to `static_libs`:

```
android_app {
    name: "DollOSSetupWizard",
    srcs: [
        "src/**/*.kt",
    ],
    resource_dirs: ["res"],
    platform_apis: true,
    privileged: true,
    certificate: "platform",
    overrides: ["SetupWizard2", "Provision", "Auditor"],
    static_libs: [
        "androidx.core_core-ktx",
        "androidx.appcompat_appcompat",
        "androidx.viewpager2_viewpager2",
        "com.google.android.material_material",
        "dollos-service-aidl",
        "dollos-ai-aidl",
    ],
    kotlincflags: ["-Xjvm-default=all"],
}
```

- [ ] **Step 2: Update SetupWizardActivity.kt to bind to DollOSAIService**

Replace the full content of `packages/apps/DollOSSetupWizard/src/org/dollos/setup/SetupWizardActivity.kt`:

```kotlin
package org.dollos.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import org.dollos.ai.IDollOSAIService
import org.dollos.service.IDollOSService

class SetupWizardActivity : AppCompatActivity() {

    private val pageKeys = listOf(
        "welcome", "wifi", "gms", "model_download",
        "api_key", "personality", "voice", "complete"
    )

    private val skippablePages = setOf("model_download", "api_key")
    private val skipTargets = mapOf(
        "api_key" to "voice"
    )

    var dollOSService: IDollOSService? = null
        private set
    var dollOSAIService: IDollOSAIService? = null
        private set

    private var isBoundToService = false
    private var isBoundToAIService = false

    private lateinit var viewPager: ViewPager2
    private lateinit var btnBack: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnSkip: TextView
    private lateinit var dotContainer: LinearLayout
    private val dots = mutableListOf<ImageView>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            dollOSService = IDollOSService.Stub.asInterface(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            dollOSService = null
        }
    }

    private val aiServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            dollOSAIService = IDollOSAIService.Stub.asInterface(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            dollOSAIService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)

        // Bind to DollOSService (for GMS page)
        val serviceIntent = Intent("org.dollos.service.IDollOSService")
        serviceIntent.setPackage("org.dollos.service")
        isBoundToService = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Bind to DollOSAIService (for API key + personality pages)
        val aiServiceIntent = Intent("org.dollos.ai.IDollOSAIService")
        aiServiceIntent.setPackage("org.dollos.ai")
        isBoundToAIService = bindService(aiServiceIntent, aiServiceConnection, Context.BIND_AUTO_CREATE)

        viewPager = findViewById(R.id.view_pager)
        btnBack = findViewById(R.id.btn_back)
        btnNext = findViewById(R.id.btn_next)
        btnSkip = findViewById(R.id.btn_skip)
        dotContainer = findViewById(R.id.dot_container)

        viewPager.adapter = SetupPagerAdapter(this)
        viewPager.isUserInputEnabled = false

        setupDots()
        updateUI(0)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateUI(position)
            }
        })

        btnNext.setOnClickListener {
            val current = viewPager.currentItem
            if (current < pageKeys.size - 1) {
                val fragment = supportFragmentManager.findFragmentByTag("f${current}")
                if (fragment is SetupPage) {
                    fragment.onNext()
                }
                viewPager.setCurrentItem(current + 1, true)
            } else {
                finishSetup()
            }
        }

        btnBack.setOnClickListener {
            val current = viewPager.currentItem
            if (current > 0) {
                viewPager.setCurrentItem(current - 1, true)
            }
        }

        btnSkip.setOnClickListener {
            val current = viewPager.currentItem
            val currentKey = pageKeys[current]
            val targetKey = skipTargets[currentKey]
            if (targetKey != null) {
                viewPager.setCurrentItem(pageKeys.indexOf(targetKey), true)
            } else {
                viewPager.setCurrentItem(current + 1, true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBoundToService) {
            unbindService(serviceConnection)
            isBoundToService = false
        }
        if (isBoundToAIService) {
            unbindService(aiServiceConnection)
            isBoundToAIService = false
        }
    }

    fun getPageIndex(key: String): Int = pageKeys.indexOf(key)

    fun navigateTo(pageKey: String) {
        val index = pageKeys.indexOf(pageKey)
        if (index >= 0) {
            viewPager.setCurrentItem(index, true)
        }
    }

    private fun setupDots() {
        dotContainer.removeAllViews()
        dots.clear()
        for (i in pageKeys.indices) {
            val dot = ImageView(this)
            val params = LinearLayout.LayoutParams(24, 24)
            params.marginStart = if (i == 0) 0 else 12
            dot.layoutParams = params
            dot.setImageResource(R.drawable.dot_indicator)
            dotContainer.addView(dot)
            dots.add(dot)
        }
    }

    private fun updateUI(position: Int) {
        for (i in dots.indices) {
            dots[i].setImageResource(
                if (i == position) R.drawable.dot_indicator_active
                else R.drawable.dot_indicator
            )
        }

        btnBack.visibility = if (position > 0) View.VISIBLE else View.GONE

        val currentKey = pageKeys[position]
        btnSkip.visibility = if (currentKey in skippablePages) View.VISIBLE else View.GONE

        btnNext.text = if (position == pageKeys.size - 1) "Get Started" else "Continue"
    }

    private fun finishSetup() {
        Settings.Global.putInt(contentResolver, Settings.Global.DEVICE_PROVISIONED, 1)
        Settings.Secure.putInt(contentResolver, Settings.Secure.USER_SETUP_COMPLETE, 1)

        packageManager.setComponentEnabledSetting(
            ComponentName(this, SetupWizardActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private inner class SetupPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = pageKeys.size
        override fun createFragment(position: Int): Fragment {
            return when (pageKeys[position]) {
                "welcome" -> WelcomePage()
                "wifi" -> WifiPage()
                "gms" -> GmsPage()
                "model_download" -> ModelDownloadPage()
                "api_key" -> ApiKeyPage()
                "personality" -> PersonalityPage()
                "voice" -> VoicePage()
                "complete" -> CompletePage()
                else -> WelcomePage()
            }
        }
    }
}

interface SetupPage {
    fun onNext()
}
```

- [ ] **Step 3: Update ApiKeyPage.kt to use DollOSAIService**

Replace the full content of `packages/apps/DollOSSetupWizard/src/org/dollos/setup/ApiKeyPage.kt`:

```kotlin
package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment

class ApiKeyPage : Fragment(), SetupPage {

    private lateinit var providerSpinner: Spinner
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText

    private val providers = listOf("Claude", "Grok", "OpenAI", "Custom")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_api_key, container, false)
        view.findViewById<TextView>(R.id.title).text = "AI Configuration"
        view.findViewById<TextView>(R.id.description).text =
            "Connect your AI provider.\nYou can configure this later in Settings."

        providerSpinner = view.findViewById(R.id.spinner_provider)
        apiKeyInput = view.findViewById(R.id.input_api_key)
        modelInput = view.findViewById(R.id.input_model)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, providers)
        providerSpinner.adapter = adapter

        return view
    }

    override fun onNext() {
        val provider = providerSpinner.selectedItem?.toString() ?: ""
        val apiKey = apiKeyInput.text?.toString() ?: ""
        val model = modelInput.text?.toString() ?: ""
        if (provider.isNotBlank() && apiKey.isNotBlank()) {
            val aiService = (activity as? SetupWizardActivity)?.dollOSAIService
            aiService?.setForegroundModel(provider, apiKey, model)
        }
    }
}
```

- [ ] **Step 4: Update page_api_key.xml to add model input**

Replace the full content of `packages/apps/DollOSSetupWizard/res/layout/page_api_key.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingLeft="40dp"
        android:paddingRight="40dp"
        android:paddingTop="120dp">

        <TextView
            android:id="@+id/title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center_horizontal"
            style="@style/SetupTitle" />

        <TextView
            android:id="@+id/description"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:gravity="center"
            android:layout_gravity="center_horizontal"
            style="@style/SetupSubtitle" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="40dp"
            android:orientation="vertical"
            style="@style/SetupCard">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Provider"
                android:textSize="13sp"
                android:textColor="?android:attr/textColorSecondary"
                android:layout_marginBottom="8dp" />

            <Spinner
                android:id="@+id/spinner_provider"
                android:layout_width="match_parent"
                android:layout_height="48dp"
                android:background="@android:color/transparent" />

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="@color/divider_light"
                android:layout_marginTop="8dp"
                android:layout_marginBottom="16dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="API Key"
                android:textSize="13sp"
                android:textColor="?android:attr/textColorSecondary"
                android:layout_marginBottom="8dp" />

            <EditText
                android:id="@+id/input_api_key"
                android:layout_width="match_parent"
                android:layout_height="48dp"
                android:inputType="textPassword"
                android:hint="Enter your API key"
                android:background="@android:color/transparent"
                android:textSize="16sp" />

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="@color/divider_light"
                android:layout_marginTop="8dp"
                android:layout_marginBottom="16dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Model"
                android:textSize="13sp"
                android:textColor="?android:attr/textColorSecondary"
                android:layout_marginBottom="8dp" />

            <EditText
                android:id="@+id/input_model"
                android:layout_width="match_parent"
                android:layout_height="48dp"
                android:inputType="text"
                android:hint="e.g., claude-sonnet-4-20250514"
                android:background="@android:color/transparent"
                android:textSize="16sp" />

        </LinearLayout>

    </LinearLayout>
</ScrollView>
```

- [ ] **Step 5: Update PersonalityPage.kt to use 5-field personality via DollOSAIService**

Replace the full content of `packages/apps/DollOSSetupWizard/src/org/dollos/setup/PersonalityPage.kt`:

```kotlin
package org.dollos.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment

class PersonalityPage : Fragment(), SetupPage {

    private lateinit var backstoryInput: EditText
    private lateinit var directiveInput: EditText
    private lateinit var dynamismSeekBar: SeekBar
    private lateinit var dynamismLabel: TextView
    private lateinit var addressInput: EditText
    private lateinit var languageSpinner: Spinner

    private val languages = listOf(
        "English", "Japanese", "Traditional Chinese", "Simplified Chinese",
        "Korean", "Spanish", "French", "German"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.page_personality, container, false)
        view.findViewById<TextView>(R.id.title).text = "Meet Your AI"
        view.findViewById<TextView>(R.id.description).text =
            "Configure your AI companion's personality.\nAll fields are optional."

        backstoryInput = view.findViewById(R.id.input_backstory)
        directiveInput = view.findViewById(R.id.input_directive)
        dynamismSeekBar = view.findViewById(R.id.seekbar_dynamism)
        dynamismLabel = view.findViewById(R.id.label_dynamism_value)
        addressInput = view.findViewById(R.id.input_address)
        languageSpinner = view.findViewById(R.id.spinner_language)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, languages)
        languageSpinner.adapter = adapter

        dynamismSeekBar.max = 100
        dynamismSeekBar.progress = 50
        dynamismLabel.text = "0.50"
        dynamismSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 100.0f
                dynamismLabel.text = String.format("%.2f", value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        return view
    }

    override fun onNext() {
        val aiService = (activity as? SetupWizardActivity)?.dollOSAIService ?: return

        val backstory = backstoryInput.text?.toString() ?: ""
        if (backstory.isNotBlank()) {
            aiService.setBackstory(backstory)
        }

        val directive = directiveInput.text?.toString() ?: ""
        if (directive.isNotBlank()) {
            aiService.setResponseDirective(directive)
        }

        val dynamism = dynamismSeekBar.progress / 100.0f
        aiService.setDynamism(dynamism)

        val address = addressInput.text?.toString() ?: ""
        if (address.isNotBlank()) {
            aiService.setAddress(address)
        }

        val language = languageSpinner.selectedItem?.toString() ?: ""
        if (language.isNotBlank()) {
            aiService.setLanguagePreference(language)
        }
    }
}
```

- [ ] **Step 6: Update page_personality.xml for 5-field layout**

Replace the full content of `packages/apps/DollOSSetupWizard/res/layout/page_personality.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingLeft="40dp"
        android:paddingRight="40dp"
        android:paddingTop="80dp"
        android:paddingBottom="40dp">

        <TextView
            android:id="@+id/title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center_horizontal"
            style="@style/SetupTitle" />

        <TextView
            android:id="@+id/description"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:gravity="center"
            android:layout_gravity="center_horizontal"
            style="@style/SetupSubtitle" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="32dp"
            android:orientation="vertical"
            style="@style/SetupCard">

            <!-- Backstory -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Backstory"
                android:textSize="13sp"
                android:textColor="?android:attr/textColorSecondary"
                android:layout_marginBottom="4dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Your AI's background and personality (2500 chars max)"
                android:textSize="11sp"
                android:textColor="?android:attr/textColorTertiary"
                android:layout_marginBottom="8dp" />

            <EditText
                android:id="@+id/input_backstory"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:minHeight="80dp"
                android:hint="Describe your AI's personality, background, motivations..."
                android:background="@android:color/transparent"
                android:textSize="15sp"
                android:inputType="textMultiLine"
                android:gravity="top"
                android:maxLength="2500" />

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="@color/divider_light"
                android:layout_marginTop="8dp"
                android:layout_marginBottom="16dp" />

            <!-- Response Directive -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Response Directive"
                android:textSize="13sp"
                android:textColor="?android:attr/textColorSecondary"
                android:layout_marginBottom="4dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Forced tone and style (150 chars max, strongest influence)"
                android:textSize="11sp"
                android:textColor="?android:attr/textColorTertiary"
                android:layout_marginBottom="8dp" />

            <EditText
                android:id="@+id/input_directive"
                android:layout_width="match_parent"
                android:layout_height="48dp"
                android:hint="e.g., Always be concise and witty"
                android:background="@android:color/transparent"
                android:textSize="15sp"
                android:inputType="text"
                android:maxLength="150" />

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="@color/divider_light"
                android:layout_marginTop="8dp"
                android:layout_marginBottom="16dp" />

            <!-- Dynamism -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical">

                <TextView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Dynamism"
                    android:textSize="13sp"
                    android:textColor="?android:attr/textColorSecondary" />

                <TextView
                    android:id="@+id/label_dynamism_value"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="0.50"
                    android:textSize="13sp"
                    android:textColor="?android:attr/textColorSecondary" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:layout_marginTop="4dp">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Stable"
                    android:textSize="11sp"
                    android:textColor="?android:attr/textColorTertiary" />

                <SeekBar
                    android:id="@+id/seekbar_dynamism"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:layout_marginLeft="8dp"
                    android:layout_marginRight="8dp" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Creative"
                    android:textSize="11sp"
                    android:textColor="?android:attr/textColorTertiary" />
            </LinearLayout>

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="@color/divider_light"
                android:layout_marginTop="12dp"
                android:layout_marginBottom="16dp" />

            <!-- Address -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="How should the AI address you?"
                android:textSize="13sp"
                android:textColor="?android:attr/textColorSecondary"
                android:layout_marginBottom="8dp" />

            <EditText
                android:id="@+id/input_address"
                android:layout_width="match_parent"
                android:layout_height="48dp"
                android:hint="Name, nickname, or honorific"
                android:background="@android:color/transparent"
                android:textSize="15sp"
                android:inputType="textPersonName"
                android:maxLength="50" />

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="@color/divider_light"
                android:layout_marginTop="8dp"
                android:layout_marginBottom="16dp" />

            <!-- Language Preference -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Response Language"
                android:textSize="13sp"
                android:textColor="?android:attr/textColorSecondary"
                android:layout_marginBottom="8dp" />

            <Spinner
                android:id="@+id/spinner_language"
                android:layout_width="match_parent"
                android:layout_height="48dp"
                android:background="@android:color/transparent" />

        </LinearLayout>

    </LinearLayout>
</ScrollView>
```

- [ ] **Step 7: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSSetupWizard
git add -A
git commit -m "feat: migrate OOBE to bind DollOSAIService for API key and 5-field personality"
```

---

## Task 10: Vendor Config and Build Integration

**Goal:** Add DollOSAIService to the vendor product makefile so it gets built and included in the system image. Add privapp permissions copy target.

**Files:**
- Modify: `vendor/dollos/dollos_bluejay.mk`

- [ ] **Step 1: Update dollos_bluejay.mk**

In `vendor/dollos/dollos_bluejay.mk`, add `DollOSAIService` to `PRODUCT_PACKAGES` and add the privapp permissions copy:

```makefile
# Inherit from GrapheneOS bluejay configuration
$(call inherit-product, device/google/bluejay/aosp_bluejay.mk)

# DollOS identity
PRODUCT_NAME := dollos_bluejay
PRODUCT_DEVICE := bluejay
PRODUCT_BRAND := DollOS
PRODUCT_MODEL := DollOS on Pixel 6a
PRODUCT_MANUFACTURER := DollOS

# DollOS version
DOLLOS_VERSION := 0.1.0
PRODUCT_SYSTEM_PROPERTIES += \
    ro.dollos.version=$(DOLLOS_VERSION) \
    persist.sys.usb.config=mtp,adb \
    ro.adb.secure=0

# DollOS packages
PRODUCT_PACKAGES += \
    DollOSService \
    DollOSAIService \
    DollOSSetupWizard

# Remove GrapheneOS apps not needed for DollOS
PRODUCT_PACKAGES_REMOVE += \
    Auditor \
    Updater

# DollOS SELinux policy
BOARD_VENDOR_SEPOLICY_DIRS += vendor/dollos/sepolicy

# Privapp permissions
PRODUCT_COPY_FILES += \
    packages/apps/DollOSService/privapp-permissions-dollos-service.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-dollos-service.xml \
    packages/apps/DollOSAIService/privapp-permissions-dollos-ai.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-dollos-ai.xml \
    packages/apps/DollOSSetupWizard/privapp-permissions-dollos-setup.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-dollos-setup.xml
```

- [ ] **Step 2: Add DollOSAIService to platform manifest**

The platform manifest at `ningyos/platform_manifest` needs a new `<project>` entry for DollOSAIService. Add to the local manifest (the same way DollOSService is added):

```xml
<project path="packages/apps/DollOSAIService" name="ningyos/DollOSAIService" remote="github" />
```

This is done in the manifest repo, not in the build tree. The agentic worker should create the git repo on GitHub and add this entry to the manifest.

- [ ] **Step 3: Commit vendor config**

```bash
cd ~/Desktop/DollOS-build/vendor/dollos
git add dollos_bluejay.mk
git commit -m "feat: add DollOSAIService to product packages and privapp permissions"
```

---

## Task 11: Deprecate Old DollOSService AI Methods

**Goal:** Mark the old `setApiKey`, `setPersonality`, `getPersonalityName` methods in DollOSService AIDL as deprecated. Keep them functional during transition but add deprecation comments. Update DollOSServiceImpl to log deprecation warnings.

**Files:**
- Modify: `packages/apps/DollOSService/aidl/org/dollos/service/IDollOSService.aidl`
- Modify: `packages/apps/DollOSService/src/org/dollos/service/DollOSServiceImpl.kt`

- [ ] **Step 1: Update IDollOSService.aidl with deprecation comments**

Replace the full content of `packages/apps/DollOSService/aidl/org/dollos/service/IDollOSService.aidl`:

```aidl
package org.dollos.service;

interface IDollOSService {
    /** Returns the DollOS version string */
    String getVersion();

    /** Returns true if the AI core is configured (API key set) */
    boolean isAiConfigured();

    /** Returns the path to the DollOS data directory */
    String getDataDirectory();

    /**
     * @deprecated Use DollOSAIService.setForegroundModel() instead.
     * Kept for backward compatibility during transition.
     */
    void setApiKey(String provider, String apiKey);

    /** Store GMS opt-in preference (called by OOBE wizard via Binder) */
    void setGmsOptIn(boolean optIn);

    /** Check if user opted into GMS */
    boolean isGmsOptedIn();

    /**
     * @deprecated Use DollOSAIService personality methods instead
     * (setBackstory, setResponseDirective, setDynamism, setAddress, setLanguagePreference).
     * Kept for backward compatibility during transition.
     */
    void setPersonality(String name, String description);

    /**
     * @deprecated Use DollOSAIService.getBackstory() or personality methods instead.
     * Kept for backward compatibility during transition.
     */
    String getPersonalityName();
}
```

- [ ] **Step 2: Update DollOSServiceImpl.kt with deprecation warnings**

In `packages/apps/DollOSService/src/org/dollos/service/DollOSServiceImpl.kt`, update the deprecated methods to log warnings:

Replace `override fun setApiKey` with:

```kotlin
    override fun setApiKey(provider: String, apiKey: String) {
        Log.w(TAG, "setApiKey() is deprecated. Use DollOSAIService.setForegroundModel() instead.")
        DollOSApp.prefs.edit()
            .putString(KEY_PROVIDER, provider)
            .putString(KEY_API_KEY, apiKey)
            .apply()
        Log.i(TAG, "API key saved for provider: $provider")
    }
```

Replace `override fun setPersonality` with:

```kotlin
    override fun setPersonality(name: String, description: String) {
        Log.w(TAG, "setPersonality() is deprecated. Use DollOSAIService personality methods instead.")
        DollOSApp.prefs.edit()
            .putString(KEY_AI_NAME, name)
            .putString(KEY_AI_DESCRIPTION, description)
            .apply()
        Log.i(TAG, "AI personality set: $name")
    }
```

Replace `override fun getPersonalityName` with:

```kotlin
    override fun getPersonalityName(): String {
        Log.w(TAG, "getPersonalityName() is deprecated. Use DollOSAIService personality methods instead.")
        return DollOSApp.prefs.getString(KEY_AI_NAME, "") ?: ""
    }
```

- [ ] **Step 3: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSService
git add -A
git commit -m "feat: deprecate old AI methods in DollOSService AIDL (migrated to DollOSAIService)"
```

---

## Task 12: Build, Flash, and Verify

**Goal:** Verify the complete build compiles, flashes to device, and all components work: DollOSAIService starts, OOBE binds to it, personality can be configured, and API key is stored via AIService.

- [ ] **Step 1: Build**

```bash
cd ~/Desktop/DollOS-build
source build/envsetup.sh
lunch dollos_bluejay-cur-userdebug
m -j$(nproc)
```

If build errors occur, fix them before proceeding. Common issues:
- AIDL import path issues: ensure `aidl/` dirs match the `local_include_dirs` in Android.bp
- Kotlin version conflicts: ensure `kotlincflags` matches other modules
- Missing AIDL stubs: ensure `dollos-ai-aidl` and `dollos-service-aidl` are in the correct `static_libs`

- [ ] **Step 2: Flash**

```bash
adb reboot bootloader
# wait for device
cd out/target/product/bluejay
fastboot flashall -w
```

- [ ] **Step 3: Verify DollOSAIService starts**

```bash
# Check service is running
adb shell dumpsys activity services org.dollos.ai

# Check logs
adb shell logcat -d | grep "DollOSAI"
```

Expected log output:
```
DollOSAIApp: DollOS AI Application initialized, version 0.1.0
DollOSAIApp: Notification channel created: dollos_ai_errors
DollOSAIService: DollOS AI Service created
DollOSAIService: Connected to DollOSService
```

- [ ] **Step 4: Verify OOBE binds to both services**

Run through OOBE on device. On the API Key page, enter a provider and key. On the Personality page, fill in fields. Check logs:

```bash
adb shell logcat -d | grep -E "DollOSAIServiceImpl|PersonalityManager"
```

Expected:
```
DollOSAIServiceImpl: Foreground model set: Claude / claude-sonnet-4-20250514
PersonalityManager: Backstory updated (xxx chars)
PersonalityManager: Response directive updated (xxx chars)
PersonalityManager: Dynamism updated: 0.5
```

- [ ] **Step 5: Verify AIDL binding from adb**

```bash
# Test personality round-trip
adb shell service call org.dollos.ai 11 s16 "Test backstory"  # setBackstory
adb shell service call org.dollos.ai 16                        # getBackstory

# Test usage stats
adb shell service call org.dollos.ai 26                        # getUsageStats
```

- [ ] **Step 6: Verify deprecation warnings in DollOSService**

```bash
adb shell logcat -d | grep "deprecated"
```

These should appear if old methods are still called.

---

## Notes

### Dependencies

- **Plan A depends on nothing** -- it is self-contained and can be implemented first.
- **Plan B (Memory System)** will fill in the memory stubs (`searchMemory`, `exportMemory`, `importMemory`, `confirmMemoryWrite`) and integrate with `SystemPromptBuilder.buildWithMemory()`.
- **Plan C (Agent + Emergency Stop)** adds `executeSystemAction` and `getAvailableActions` to DollOSService, which Plan A's `loadToolDefinitions()` and `handleToolCalls()` already expect.

### What works after Plan A

1. DollOSAIService starts and connects to DollOSService via Binder
2. AIDL interface fully functional (all methods implemented or stubbed)
3. OOBE writes API key and 5-field personality to DollOSAIService
4. `sendMessage()` sends to configured LLM provider with streaming
5. Personality system constructs system prompt from all 5 fields
6. Per-provider temperature clamping (Claude 0.3-1.0, OpenAI/Grok 0.3-1.2)
7. Usage tracking records every API call
8. Budget controls enforce warning and hard limit (daily/monthly)
9. API errors surfaced via system notifications
10. `pauseAll()` / `resumeAll()` with 3s synchronous timeout
11. Tool calls forwarded to DollOSService (when Plan C is also deployed)
12. Old DollOSService AI methods deprecated with log warnings

### What does NOT work yet (needs Plan B or C)

- Memory search, export, import (stubs return empty)
- Memory-augmented system prompts (only personality fields used)
- Agent action execution (requires Plan C's DollOSService updates)
- Emergency stop UI (requires Plan C's TaskManagerActivity)
- Conversation history persistence (Plan B scope)
- Context window management and compression (Plan B scope)
