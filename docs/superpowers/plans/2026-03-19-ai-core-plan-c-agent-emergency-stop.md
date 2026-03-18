# AI Core Plan C: Agent System + Emergency Stop

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the system control infrastructure for DollOS AI: agent Action interface for device operations, DollOSService updates for executing actions on behalf of AIService, and emergency stop mechanism (double-click power button -> AI Task Manager).

**Architecture:** Agent actions are defined as an extensible Action interface in DollOSService. The emergency stop uses AOSP's built-in `MULTI_PRESS_POWER_LAUNCH_TARGET_ACTIVITY` config (no framework modification needed) to launch a Task Manager Activity. DollOSService gains new AIDL methods for system action execution and task management.

**Tech Stack:** Kotlin, AIDL (Binder IPC), AOSP resource overlay (config_doublePressOnPowerBehavior), Android System Services (AlarmManager, WifiManager, BluetoothManager)

---

## File Structure

### DollOSService (modify existing)

```
packages/apps/DollOSService/
  aidl/org/dollos/service/
    IDollOSService.aidl              -- add executeSystemAction, showTaskManager
    IDollOSAICallback.aidl           -- new: callback interface for AI events
  src/org/dollos/service/
    DollOSApp.kt                     -- existing (no change)
    DollOSService.kt                 -- add action execution + task manager binding
    DollOSServiceImpl.kt             -- add new AIDL method implementations
    action/
      Action.kt                      -- Action interface definition
      ActionRegistry.kt              -- registers and looks up available actions
      OpenAppAction.kt               -- open app by package/name
      SetAlarmAction.kt              -- set alarm via AlarmManager
      ToggleWifiAction.kt            -- toggle WiFi on/off
      ToggleBluetoothAction.kt       -- toggle Bluetooth on/off
    taskmanager/
      TaskManagerActivity.kt         -- emergency stop UI (modal)
      AITask.kt                      -- data class for AI task info
  res/layout/
    activity_task_manager.xml        -- task manager modal layout
    item_ai_task.xml                 -- individual task item layout
  AndroidManifest.xml                -- add TaskManagerActivity
```

### Resource Overlay (new)

```
vendor/dollos/overlay/
  frameworks/base/core/res/res/values/
    config.xml                       -- override doublePressOnPower config
```

### Vendor Config (modify existing)

```
vendor/dollos/
  dollos_bluejay.mk                  -- add overlay path
```

---

## Task 1: Action Interface and Registry

**Goal:** Define the extensible Action interface and a registry to manage available actions.

**Files:**
- Create: `packages/apps/DollOSService/src/org/dollos/service/action/Action.kt`
- Create: `packages/apps/DollOSService/src/org/dollos/service/action/ActionRegistry.kt`

- [ ] **Step 1: Create Action interface**

Create `packages/apps/DollOSService/src/org/dollos/service/action/Action.kt`:

```kotlin
package org.dollos.service.action

import android.content.Context
import org.json.JSONObject

data class ActionResult(
    val success: Boolean,
    val message: String
)

interface Action {
    val id: String
    val name: String
    val description: String
    val confirmRequired: Boolean

    fun execute(context: Context, params: JSONObject): ActionResult
}
```

- [ ] **Step 2: Create ActionRegistry**

Create `packages/apps/DollOSService/src/org/dollos/service/action/ActionRegistry.kt`:

```kotlin
package org.dollos.service.action

import android.util.Log

class ActionRegistry {

    companion object {
        private const val TAG = "ActionRegistry"
    }

    private val actions = mutableMapOf<String, Action>()

    fun register(action: Action) {
        actions[action.id] = action
        Log.i(TAG, "Registered action: ${action.id}")
    }

    fun get(id: String): Action? = actions[id]

    fun getAll(): List<Action> = actions.values.toList()

    fun toToolDescriptions(): String {
        val tools = actions.values.map { action ->
            """{"id":"${action.id}","name":"${action.name}","description":"${action.description}","confirmRequired":${action.confirmRequired}}"""
        }
        return "[${tools.joinToString(",")}]"
    }
}
```

- [ ] **Step 3: Commit**

```bash
cd ~/Desktop/DollOS-build/packages/apps/DollOSService
git add src/org/dollos/service/action/
git commit -m "feat: add Action interface and ActionRegistry"
```

---

## Task 2: Four Action Implementations

**Goal:** Implement the four first-version agent actions.

**Files:**
- Create: `packages/apps/DollOSService/src/org/dollos/service/action/OpenAppAction.kt`
- Create: `packages/apps/DollOSService/src/org/dollos/service/action/SetAlarmAction.kt`
- Create: `packages/apps/DollOSService/src/org/dollos/service/action/ToggleWifiAction.kt`
- Create: `packages/apps/DollOSService/src/org/dollos/service/action/ToggleBluetoothAction.kt`

- [ ] **Step 1: Create OpenAppAction**

```kotlin
package org.dollos.service.action

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject

class OpenAppAction : Action {
    override val id = "open_app"
    override val name = "Open App"
    override val description = "Open an application by package name or app name"
    override val confirmRequired = false

    companion object {
        private const val TAG = "OpenAppAction"
    }

    override fun execute(context: Context, params: JSONObject): ActionResult {
        val packageName = params.optString("package_name", "")
        val appName = params.optString("app_name", "")

        val intent = if (packageName.isNotBlank()) {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } else if (appName.isNotBlank()) {
            findAppByName(context, appName)
        } else {
            return ActionResult(false, "No package name or app name provided")
        }

        if (intent == null) {
            return ActionResult(false, "App not found: ${packageName.ifBlank { appName }}")
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.i(TAG, "Opened app: ${packageName.ifBlank { appName }}")
        return ActionResult(true, "App opened")
    }

    private fun findAppByName(context: Context, name: String): Intent? {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.find {
            pm.getApplicationLabel(it).toString().equals(name, ignoreCase = true)
        }
        return match?.let { pm.getLaunchIntentForPackage(it.packageName) }
    }
}
```

- [ ] **Step 2: Create SetAlarmAction**

```kotlin
package org.dollos.service.action

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import org.json.JSONObject

class SetAlarmAction : Action {
    override val id = "set_alarm"
    override val name = "Set Alarm"
    override val description = "Set an alarm with specified time and optional label"
    override val confirmRequired = true

    companion object {
        private const val TAG = "SetAlarmAction"
    }

    override fun execute(context: Context, params: JSONObject): ActionResult {
        val hour = params.optInt("hour", -1)
        val minute = params.optInt("minute", 0)
        val label = params.optString("label", "DollOS Alarm")

        if (hour < 0 || hour > 23) {
            return ActionResult(false, "Invalid hour: $hour")
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        Log.i(TAG, "Alarm set for $hour:${minute.toString().padStart(2, '0')} - $label")
        return ActionResult(true, "Alarm set for $hour:${minute.toString().padStart(2, '0')}")
    }
}
```

- [ ] **Step 3: Create ToggleWifiAction**

```kotlin
package org.dollos.service.action

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject

class ToggleWifiAction : Action {
    override val id = "toggle_wifi"
    override val name = "Toggle WiFi"
    override val description = "Turn WiFi on or off"
    override val confirmRequired = true

    companion object {
        private const val TAG = "ToggleWifiAction"
    }

    override fun execute(context: Context, params: JSONObject): ActionResult {
        val enable = params.optBoolean("enable", true)
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = enable

        val state = if (enable) "on" else "off"
        Log.i(TAG, "WiFi turned $state")
        return ActionResult(true, "WiFi turned $state")
    }
}
```

- [ ] **Step 4: Create ToggleBluetoothAction**

```kotlin
package org.dollos.service.action

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import org.json.JSONObject

class ToggleBluetoothAction : Action {
    override val id = "toggle_bluetooth"
    override val name = "Toggle Bluetooth"
    override val description = "Turn Bluetooth on or off"
    override val confirmRequired = true

    companion object {
        private const val TAG = "ToggleBluetoothAction"
    }

    override fun execute(context: Context, params: JSONObject): ActionResult {
        val enable = params.optBoolean("enable", true)
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return ActionResult(false, "Bluetooth not available")

        @Suppress("DEPRECATION")
        val result = if (enable) adapter.enable() else adapter.disable()

        val state = if (enable) "on" else "off"
        if (result) {
            Log.i(TAG, "Bluetooth turned $state")
            return ActionResult(true, "Bluetooth turned $state")
        } else {
            return ActionResult(false, "Failed to turn Bluetooth $state")
        }
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add src/org/dollos/service/action/
git commit -m "feat: add four agent actions (open app, alarm, WiFi, Bluetooth)"
```

---

## Task 3: Update DollOSService AIDL and Implementation

**Goal:** Add `executeSystemAction` and task manager methods to DollOSService AIDL. Register actions on startup.

**Files:**
- Modify: `packages/apps/DollOSService/aidl/org/dollos/service/IDollOSService.aidl`
- Modify: `packages/apps/DollOSService/src/org/dollos/service/DollOSServiceImpl.kt`
- Modify: `packages/apps/DollOSService/src/org/dollos/service/DollOSApp.kt`
- Modify: `packages/apps/DollOSService/AndroidManifest.xml`

- [ ] **Step 1: Update IDollOSService.aidl**

Add to existing interface:

```aidl
    /** Execute a system action on behalf of DollOSAIService */
    String executeSystemAction(String actionId, String paramsJson);

    /** Get list of available actions as JSON */
    String getAvailableActions();

    /** Show the AI Task Manager modal */
    void showTaskManager();
```

- [ ] **Step 2: Update DollOSApp.kt**

Add ActionRegistry initialization in `onCreate()`:

```kotlin
// Add to companion object:
lateinit var actionRegistry: ActionRegistry
    private set

// Add to onCreate():
actionRegistry = ActionRegistry()
actionRegistry.register(OpenAppAction())
actionRegistry.register(SetAlarmAction())
actionRegistry.register(ToggleWifiAction())
actionRegistry.register(ToggleBluetoothAction())
Log.i(TAG, "Registered ${actionRegistry.getAll().size} actions")
```

- [ ] **Step 3: Update DollOSServiceImpl.kt**

Add implementations:

```kotlin
override fun executeSystemAction(actionId: String, paramsJson: String): String {
    val action = DollOSApp.actionRegistry.get(actionId)
        ?: return """{"success":false,"message":"Unknown action: $actionId"}"""

    val params = JSONObject(paramsJson)
    val result = action.execute(DollOSApp.instance, params)
    return """{"success":${result.success},"message":"${result.message}"}"""
}

override fun getAvailableActions(): String {
    return DollOSApp.actionRegistry.toToolDescriptions()
}

override fun showTaskManager() {
    val intent = Intent(DollOSApp.instance, TaskManagerActivity::class.java)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    DollOSApp.instance.startActivity(intent)
}
```

- [ ] **Step 4: Update DollOSApp.kt to expose context**

Add to DollOSApp:

```kotlin
companion object {
    // ... existing fields ...
    lateinit var instance: DollOSApp
        private set
}

override fun onCreate() {
    super.onCreate()
    instance = this
    // ... rest of existing onCreate ...
}
```

- [ ] **Step 5: Add permissions to AndroidManifest.xml**

Add these permissions:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.SET_ALARM" />
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add executeSystemAction AIDL, register actions on startup"
```

---

## Task 4: AI Task Manager Data Model

**Goal:** Define the data model for AI tasks that the Task Manager displays.

**Files:**
- Create: `packages/apps/DollOSService/src/org/dollos/service/taskmanager/AITask.kt`

- [ ] **Step 1: Create AITask data class**

```kotlin
package org.dollos.service.taskmanager

import org.json.JSONArray
import org.json.JSONObject

data class AITask(
    val id: String,
    val name: String,
    val description: String,
    val startTime: Long,
    val tokenUsage: Long,
    val estimatedCost: Double,
    val conversationContext: String,
    val status: Status
) {
    enum class Status {
        RUNNING, PAUSED, PENDING_CONFIRM
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("startTime", startTime)
            put("tokenUsage", tokenUsage)
            put("estimatedCost", estimatedCost)
            put("conversationContext", conversationContext)
            put("status", status.name)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): AITask {
            return AITask(
                id = json.getString("id"),
                name = json.getString("name"),
                description = json.optString("description", ""),
                startTime = json.optLong("startTime", 0),
                tokenUsage = json.optLong("tokenUsage", 0),
                estimatedCost = json.optDouble("estimatedCost", 0.0),
                conversationContext = json.optString("conversationContext", ""),
                status = Status.valueOf(json.optString("status", "RUNNING"))
            )
        }

        fun listFromJson(jsonStr: String): List<AITask> {
            val arr = JSONArray(jsonStr)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        fun listToJson(tasks: List<AITask>): String {
            val arr = JSONArray()
            tasks.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/org/dollos/service/taskmanager/
git commit -m "feat: add AITask data model for Task Manager"
```

---

## Task 5: Task Manager Activity UI

**Goal:** Create the Task Manager modal activity that displays when emergency stop is triggered.

**Files:**
- Create: `packages/apps/DollOSService/src/org/dollos/service/taskmanager/TaskManagerActivity.kt`
- Create: `packages/apps/DollOSService/res/layout/activity_task_manager.xml`
- Create: `packages/apps/DollOSService/res/layout/item_ai_task.xml`
- Modify: `packages/apps/DollOSService/AndroidManifest.xml`

- [ ] **Step 1: Create activity_task_manager.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#80000000"
    android:clickable="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:orientation="vertical"
        android:background="@android:color/white"
        android:paddingTop="24dp"
        android:paddingBottom="40dp"
        android:paddingLeft="24dp"
        android:paddingRight="24dp"
        android:elevation="8dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="AI Activity"
            android:textSize="24sp"
            android:fontFamily="sans-serif-medium"
            android:textColor="#000000" />

        <TextView
            android:id="@+id/status_text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="All AI tasks paused"
            android:textSize="14sp"
            android:textColor="#666666" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/task_list"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:maxHeight="400dp" />

        <TextView
            android:id="@+id/empty_text"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="No active AI tasks"
            android:textSize="16sp"
            android:textColor="#999999"
            android:gravity="center"
            android:visibility="gone" />

        <TextView
            android:id="@+id/btn_resume"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:text="Resume All"
            android:textSize="17sp"
            android:fontFamily="sans-serif-medium"
            android:textColor="#FFFFFF"
            android:background="@drawable/button_primary_background"
            android:gravity="center"
            android:paddingTop="14dp"
            android:paddingBottom="14dp" />

    </LinearLayout>
</FrameLayout>
```

- [ ] **Step 2: Create item_ai_task.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp"
    android:layout_marginBottom="8dp"
    android:background="#F5F5F5">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <TextView
            android:id="@+id/task_name"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textSize="16sp"
            android:fontFamily="sans-serif-medium"
            android:textColor="#000000" />

        <TextView
            android:id="@+id/btn_cancel"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Cancel"
            android:textSize="14sp"
            android:textColor="#FF3B30"
            android:padding="8dp" />

    </LinearLayout>

    <TextView
        android:id="@+id/task_description"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textSize="14sp"
        android:textColor="#666666" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/task_time"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textSize="12sp"
            android:textColor="#999999" />

        <TextView
            android:id="@+id/task_tokens"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="12sp"
            android:textColor="#999999" />

    </LinearLayout>

</LinearLayout>
```

- [ ] **Step 3: Create TaskManagerActivity.kt**

```kotlin
package org.dollos.service.taskmanager

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.dollos.service.R
import android.view.LayoutInflater
import android.view.ViewGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskManagerActivity : Activity() {

    private lateinit var taskList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var statusText: TextView
    private val tasks = mutableListOf<AITask>()
    private lateinit var adapter: TaskAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen, like power menu
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR)

        setContentView(R.layout.activity_task_manager)

        taskList = findViewById(R.id.task_list)
        emptyText = findViewById(R.id.empty_text)
        statusText = findViewById(R.id.status_text)

        adapter = TaskAdapter(tasks) { task -> cancelTask(task) }
        taskList.layoutManager = LinearLayoutManager(this)
        taskList.adapter = adapter

        findViewById<TextView>(R.id.btn_resume).setOnClickListener {
            resumeAndFinish()
        }

        // Tap outside the modal to dismiss (resume all)
        findViewById<View>(android.R.id.content).setOnClickListener {
            resumeAndFinish()
        }

        loadTasks()
    }

    private fun loadTasks() {
        // TODO: In future, get tasks from DollOSAIService via Binder
        // For now, show empty state -- AIService doesn't exist yet
        val tasksJson = intent.getStringExtra("tasks_json") ?: "[]"
        tasks.clear()
        tasks.addAll(AITask.listFromJson(tasksJson))

        if (tasks.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            taskList.visibility = View.GONE
            statusText.text = "No active AI tasks"
        } else {
            emptyText.visibility = View.GONE
            taskList.visibility = View.VISIBLE
            statusText.text = "${tasks.size} task(s) paused"
        }
        adapter.notifyDataSetChanged()
    }

    private fun cancelTask(task: AITask) {
        tasks.remove(task)
        adapter.notifyDataSetChanged()
        // TODO: Send cancel signal to DollOSAIService
        if (tasks.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            taskList.visibility = View.GONE
            statusText.text = "All tasks cancelled"
        }
    }

    private fun resumeAndFinish() {
        // TODO: Send resume signal to DollOSAIService
        finish()
    }

    private inner class TaskAdapter(
        private val tasks: List<AITask>,
        private val onCancel: (AITask) -> Unit
    ) : RecyclerView.Adapter<TaskViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ai_task, parent, false)
            return TaskViewHolder(view)
        }

        override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
            holder.bind(tasks[position], onCancel)
        }

        override fun getItemCount() = tasks.size
    }

    private class TaskViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameText: TextView = view.findViewById(R.id.task_name)
        private val descText: TextView = view.findViewById(R.id.task_description)
        private val timeText: TextView = view.findViewById(R.id.task_time)
        private val tokensText: TextView = view.findViewById(R.id.task_tokens)
        private val cancelBtn: TextView = view.findViewById(R.id.btn_cancel)

        fun bind(task: AITask, onCancel: (AITask) -> Unit) {
            nameText.text = task.name
            descText.text = task.description
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            timeText.text = "Started: ${sdf.format(Date(task.startTime))}"
            tokensText.text = "${task.tokenUsage} tokens (~$${String.format("%.4f", task.estimatedCost)})"
            cancelBtn.setOnClickListener { onCancel(task) }
        }
    }
}
```

- [ ] **Step 4: Add TaskManagerActivity to AndroidManifest.xml**

Add inside `<application>`:

```xml
<activity
    android:name=".taskmanager.TaskManagerActivity"
    android:exported="true"
    android:theme="@android:style/Theme.Translucent.NoTitleBar"
    android:excludeFromRecents="true"
    android:launchMode="singleInstance"
    android:showOnLockScreen="true" />
```

- [ ] **Step 5: Create button_primary_background.xml drawable**

Create `packages/apps/DollOSService/res/drawable/button_primary_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="#33000000">
    <item>
        <shape android:shape="rectangle">
            <corners android:radius="24dp" />
            <solid android:color="#FF000000" />
        </shape>
    </item>
</ripple>
```

- [ ] **Step 6: Add RecyclerView dependency to Android.bp**

Add `"androidx.recyclerview_recyclerview"` to `static_libs`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add Task Manager Activity with emergency stop UI"
```

---

## Task 6: Power Button Double-Click Configuration

**Goal:** Configure AOSP to launch Task Manager on power button double-click using resource overlay.

**Files:**
- Create: `vendor/dollos/overlay/frameworks/base/core/res/res/values/config.xml`
- Modify: `vendor/dollos/dollos_bluejay.mk`

- [ ] **Step 1: Create resource overlay**

Create `vendor/dollos/overlay/frameworks/base/core/res/res/values/config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- DollOS: double-press power button launches AI Task Manager -->
    <integer name="config_doublePressOnPowerBehavior">3</integer>
    <string name="config_doublePressOnPowerTargetActivity" translatable="false">org.dollos.service/.taskmanager.TaskManagerActivity</string>
</resources>
```

- [ ] **Step 2: Add overlay to product makefile**

Add to `vendor/dollos/dollos_bluejay.mk` (add or update PRODUCT_PACKAGE_OVERLAYS):

```makefile
# DollOS framework overlay (power button config)
PRODUCT_PACKAGE_OVERLAYS += vendor/dollos/overlay
```

- [ ] **Step 3: Commit**

```bash
cd ~/Desktop/DollOS-build/vendor/dollos
git add overlay/ dollos_bluejay.mk
git commit -m "feat: configure power button double-click to launch AI Task Manager"
```

---

## Task 7: Update DollOSService Permissions

**Goal:** Add required permissions for agent actions to privapp-permissions.

**Files:**
- Modify: `packages/apps/DollOSService/privapp-permissions-dollos-service.xml`

- [ ] **Step 1: Update privapp-permissions**

Add to the existing permissions file:

```xml
<permission name="android.permission.BLUETOOTH" />
<permission name="android.permission.BLUETOOTH_ADMIN" />
<permission name="android.permission.BLUETOOTH_CONNECT" />
<permission name="android.permission.CHANGE_WIFI_STATE" />
<permission name="android.permission.ACCESS_WIFI_STATE" />
<permission name="android.permission.SET_ALARM" />
<permission name="android.permission.SYSTEM_ALERT_WINDOW" />
```

- [ ] **Step 2: Commit**

```bash
git add privapp-permissions-dollos-service.xml
git commit -m "feat: add agent action permissions (WiFi, Bluetooth, alarm, overlay)"
```

---

## Task 8: Build, Flash, and Verify

**Goal:** Verify all components work together on device.

- [ ] **Step 1: Build**

```bash
cd ~/Desktop/DollOS-build
source build/envsetup.sh
lunch dollos_bluejay-cur-userdebug
m -j$(nproc)
```

- [ ] **Step 2: Flash**

```bash
adb reboot bootloader
# wait 10s
cd out/target/product/bluejay
fastboot flashall -w
```

- [ ] **Step 3: Verify power button double-click**

After boot + OOBE, double-click the power button. The AI Task Manager should appear showing "No active AI tasks" with a "Resume All" button.

- [ ] **Step 4: Verify via adb**

```bash
# Check actions registered
adb shell service call org.dollos.service 10  # getAvailableActions

# Check Task Manager can be launched manually
adb shell am start -n org.dollos.service/.taskmanager.TaskManagerActivity

# Check DollOSService log
adb shell logcat -d | grep "ActionRegistry\|DollOSApp"
```

- [ ] **Step 5: Commit verification**

Create `docs/verification-plan-c.md` with test results.

```bash
cd ~/Desktop/DollOS
git add docs/verification-plan-c.md
git commit -m "docs: add Plan C verification results"
```

---

## Notes

### Dependencies for Plan A and B

After Plan C is complete:
- Plan A (DollOSAIService) will call `executeSystemAction` via Binder to DollOSService
- Plan A will register an `IDollOSAICallback` for the Task Manager to get live task data
- Plan B (Memory System) will add background tasks visible in the Task Manager

### What the Task Manager shows now vs later

Currently the Task Manager shows a static list (or empty). Once DollOSAIService exists (Plan A), it will:
1. Call `pauseAll()` on AIService when Task Manager opens
2. Get live task list from AIService via `getActiveTasks()`
3. Send cancel/resume signals back to AIService

The current implementation has `TODO` comments for these integration points.
