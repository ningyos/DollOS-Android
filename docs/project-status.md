# DollOS 專案狀態

> 這份文件用來讓 AI 助手在 context 重置後快速了解專案全貌和當前進度。
> 最後更新：2026-03-25

## 專案概述

DollOS（NingyoOS）是一個跨裝置的 AI 伴侶生態系統。AI 伴侶有兩個載體：

- **電腦（DollOS-Server）** — AI 的家，GPU 驅動的重度推理（vLLM）、TTS、STT、Vision、Embedding
- **手機（DollOS-Android）** — AI 外出時的身體，基於 **GrapheneOS Android 16** 的 Pixel 手機 OS，具備系統操控、感測器、輕量 LLM、3D 角色顯示

記憶在兩端同步，不論在哪個裝置互動都是同一個 AI。

- **GitHub Org:** `https://github.com/ningyos/`
- **目標裝置:** Pixel 6a (bluejay)
- **版本:** 0.1.0
- **Base OS:** GrapheneOS Android 16

## GitHub Repo 一覽

### Android 端（手機 = 外出時的身體）

| Repo | 用途 |
|------|------|
| `DollOS-Android` | AOSP overlay configs、設計文件、實作計畫（本 repo） |
| `DollOSAIService` | AI 核心：LLM client、人格、記憶、對話引擎、agent |
| `DollOSService` | 系統服務：WiFi/BT、鬧鐘、app 啟動、accessibility 自動化、emergency stop |
| `DollOSSetupWizard` | OOBE 設定精靈 |
| `DollOSLauncher` | 3D AI Launcher（Filament 渲染、角色顯示、對話泡泡）— 尚未公開 |
| `vendor_dollos` | Framework overlay（電源選單）、SELinux policy |
| `device_dollos_bluejay` | Pixel 6a device makefile + SELinux |

### 電腦端（伺服器 = AI 的家）

| Repo | 用途 |
|------|------|
| `DollOS-Server` | AI 後端（原 smolGura）：vLLM 推理、TTS、STT、Vision、Embedding、Web UI |
| `luxtts-onnx` | 輕量 ONNX TTS，免 PyTorch，voice cloning，多語言（EN+CN） |
| `fish-tts` | Fish-Speech TTS：DualARTransformer + DAC vocoder，pipeline streaming |

### 其他

| Repo | 用途 |
|------|------|
| `DollOS` | Umbrella repo（文件、設計、sync.sh） |
| `tuna` | Fine-tuning 工具（休眠中） |

## 本 Repo 目錄結構

```
~/Projects/DollOS-Android/
  aosp/
    packages/apps/DollOSService/       ← 系統服務原始碼
    packages/apps/DollOSSetupWizard/   ← OOBE 原始碼
    vendor/dollos/                     ← vendor overlay + SELinux
    device/dollos/bluejay/             ← Pixel 6a device config
  docs/                                ← 設計文件、實作計畫
```

Build tree 在 `~/Projects/DollOS-build/`：
```
~/Projects/DollOS-build/
  .repo/local_manifests/dollos.xml   ← DollOS 自訂 repo
  packages/apps/DollOSService/       ← 系統服務
  packages/apps/DollOSSetupWizard/   ← OOBE 設定精靈
  packages/apps/Settings/            ← 有修改（AI Settings 頁面）
  frameworks/base/                   ← 有修改（電源選單 AI 按鈕）
  external/DollOSAIService/          ← AI Service（local_manifests sync）
  device/dollos/bluejay/             ← Pixel 6a device config
  vendor/dollos/                     ← vendor overlay + SELinux
  build/make/                        ← build system（移除 SetupWizard2）
```

## DollOSAIService（AI Core）

獨立 Gradle 專案，prebuilt APK 整合進 AOSP。AIDL 在 repo 根目錄，Gradle 和 AOSP 共用，零複製。

**架構：**
```
ningyos/DollOSAIService/
  aidl/org/dollos/ai/           ← AIDL（唯一 source of truth）
  Android.bp                    ← dollos-ai-aidl + android_app_import
  app/                          ← Gradle app module
  prebuilt/DollOSAIService.apk  ← Gradle build 產出
```

**功能（Plan A — LLM + Personality）：**
- **LLM Client** — 多模型（Claude, OpenAI, Grok, Custom），suspend API，SSE streaming，coroutine delay retry
- **Personality** — 5 欄位（backstory, directive, temperature/dynamism, address, language）
- **Usage Tracking** — Room SQLite，90 天自動清理，daily/monthly 統計
- **Budget** — warning threshold + hard limit，Android notification
- **Tool Calling** — 透過 DollOSService 執行 system actions，確認流程（60s timeout）
- **AI Stop** — BroadcastReceiver 接收 `ACTION_AI_STOP`，呼叫 `pauseAll()`

**功能（Plan B — Memory + Conversation）：**
- **Memory System** — memsearch 架構，三層 Markdown 記憶（CORE/DAILY/DEEP）
  - sqlite-vec vector search（384 dims）+ FTS5 BM25 keyword search
  - Hybrid search: RRF（70% vector + 30% BM25，union）
  - SHA-256 content hash dedup，1500 char chunking by heading
  - Serialized write queue + pending_writes.json recovery
  - Cloud embedding（OpenAI text-embedding-3-small）+ local ONNX placeholder
  - Memory export/import via ZIP + ParcelFileDescriptor
- **Conversation Engine** — 日期分段對話，Room 持久化
  - 多輪對話（sendMessage 現在保持 context）
  - Context compression at 75% capacity（async，foreground model）
  - Memory context 自動注入 LLM system prompt
  - Idle timer（5 min）觸發背景記憶提取

**AIDL 介面：** `IDollOSAIService` + `IDollOSAICallback`

**Build 流程：**
```bash
cd ~/Projects/DollOSAIService
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release-unsigned.apk prebuilt/DollOSAIService.apk
# 同步到 AOSP tree
rsync -av --exclude='.gradle' --exclude='build' --exclude='local.properties' \
    ~/Projects/DollOSAIService/ ~/Projects/DollOS-build/external/DollOSAIService/
```

## DollOSService 功能

- **AIDL 介面:** `IDollOSService`
- **Action System:** OpenApp, SetAlarm, ToggleWiFi, ToggleBluetooth
- **Task Manager Activity:** 從電源選單 AI Activity 按鈕開啟
- **Accessibility Service（Plan C/D）：**
  - `DollOSAccessibilityService` — 自動啟用骨架
  - `NodeReader` — accessibility tree → JSON 序列化
  - `UIExecutor` — click, swipe, type, gesture, global actions
  - `ScreenCapture` — 實體螢幕 + VirtualDisplay 截圖
  - `VirtualDisplayManager` — 建立/銷毀/啟動 virtual display
  - `AppEventMonitor` — 追蹤前景 app 變更
  - `TakeoverManager` — overlay bar, edge glow, touch block, interrupt modal
- **Notification System（Plan D v2）：**
  - `NotificationRouter` — context-aware notification routing
  - `NotificationLevel`, `QuietHoursConfig`, `TTSInterface`
- **Rule Engine（Plan D v2）：**
  - `Rule`, `RuleEntity`, `RuleDao` — SQLite 持久化
  - `RuleEngine` — programmable event evaluation with debounce
  - AIDL rule management methods

## AI Settings（原生整合在 Settings app）

在 `packages/apps/Settings` 裡的 `DollOSAISettingsFragment`（DashboardFragment），完全整合在系統設定中。

**頁面結構：**
1. **Stats Card** — 今日/本月 token 數 + active model + 7 天長條圖（UsageBarChartView）
2. **Personality** — Backstory, Response directive, Temperature slider, Address, Language
3. **API & Model** — Provider（Claude/OpenAI/Grok/Custom）, API Key, Model
4. **Budget** — Warning threshold + period, Hard limit + period

透過 AIDL bind 到 DollOSAIService 讀寫設定。

## OOBE 頁面流程

```
welcome → theme → wifi → gms → model_download → api_key → personality → voice → complete
```

- `api_key` / `personality` 頁面透過 DollOSAIService AIDL 設定（無 fallback）
- `theme`: Light/Dark/Auto，用 `UiModeManager`
- `gms`: sandboxed Google Play 開關
- `finishSetup()` 設定 monochrome 系統色彩 + provisioned

## 電源選單（frameworks/base 修改）

- **AI Activity** (`aiactivity`) — 開啟 TaskManagerActivity
- **AI Stop** (`aistop`) — broadcast + toast

## Character Pack System

`.doll` 檔案 — zip bundle 包含：
- `manifest.json` — 角色 metadata
- `personality.json` — 人格設定
- `voice.json` — 語音設定
- `scene.json` — 場景設定
- 3D 模型（glTF .glb）、動畫、縮圖
- Wake word config

使用者可匯入/匯出/切換角色包。

## AI Launcher

基於 Google Filament 的 3D Launcher：
- 3D 角色渲染 + 對話泡泡
- App drawer
- Character picker
- Repo: `DollOSLauncher`（尚未公開）

## DollOS-Server（電腦端）

原名 smolGura，模組化 AI 伴侶後端：
- **推理:** vLLM
- **TTS:** fish-tts / luxtts-onnx
- **STT:** FunASR
- **Embedding:** bge-m3
- **基礎設施:** Docker（RabbitMQ, Redis, Milvus）
- **介面:** Web UI
- 手機與電腦間將透過 **DollOS Protocol**（設計中）通訊

## Build 指令

```bash
cd ~/Projects/DollOS-build
source build/envsetup.sh
lunch dollos_bluejay-bp2a-userdebug
m -j$(nproc)                    # 全系統 build
m Settings -j$(nproc)           # Settings（AI 設定頁）
m DollOSSetupWizard -j$(nproc)  # OOBE
m DollOSService -j$(nproc)      # 系統服務
m DollOSAIService -j$(nproc)    # AI 服務（prebuilt）
m SystemUI -j$(nproc)           # 電源選單
```

**注意：** build 指令不能接 `| tail` 等 pipe。背景執行用 `run_in_background`。

## 刷機流程

```bash
ADB=~/Projects/DollOS-build/out/host/linux-x86/bin/adb
$ADB root && $ADB remount

# 同步整個分區
ANDROID_PRODUCT_OUT=~/Projects/DollOS-build/out/target/product/bluejay $ADB sync system_ext

# 或推送單一模組
$ADB push out/target/product/bluejay/system_ext/priv-app/Settings/Settings.apk \
    /system_ext/priv-app/Settings/Settings.apk

$ADB reboot
```

## 設計文件

| 文件 | 內容 |
|------|------|
| `docs/superpowers/specs/2026-03-17-dollos-base-design.md` | DollOS Base 完整設計 |
| `docs/superpowers/specs/2026-03-19-ai-core-design.md` | AI Core 架構設計 |
| `docs/superpowers/specs/2026-03-23-embedding-system-design.md` | Embedding System 設計 |
| `docs/superpowers/specs/2026-03-24-plan-d-v2-design.md` | Plan D v2 — UI 操作、智慧通知、可程式化事件 |
| `docs/superpowers/specs/2026-03-24-character-pack-design.md` | Character Pack System 設計 |
| `docs/superpowers/specs/2026-03-24-ai-launcher-design.md` | AI Launcher 設計 |
| `docs/superpowers/plans/2026-03-17-dollos-base.md` | Base 實作計畫 |
| `docs/superpowers/plans/2026-03-19-ai-core-plan-a-*.md` | AI Service + LLM + Personality |
| `docs/superpowers/plans/2026-03-19-ai-core-plan-b-*.md` | Memory + Conversation |
| `docs/superpowers/plans/2026-03-19-ai-core-plan-c-*.md` | Agent + Emergency Stop |
| `docs/superpowers/plans/2026-03-19-ai-core-plan-d-*.md` | 整合計畫 |
| `docs/superpowers/plans/2026-03-22-oobe-theme-page.md` | OOBE Theme 頁面 |
| `docs/superpowers/plans/2026-03-23-embedding-system.md` | Embedding System 實作 |
| `docs/superpowers/plans/2026-03-23-ai-core-plan-d-background-work.md` | Plan D 背景工作系統 |
| `docs/superpowers/plans/2026-03-24-plan-d-v2-ui-notification-rules.md` | Plan D v2 UI/通知/規則 |
| `docs/superpowers/plans/2026-03-24-character-pack-system.md` | Character Pack 實作 |
| `docs/superpowers/plans/2026-03-24-ai-launcher.md` | AI Launcher 實作 |

## 開發階段

1. **DollOS Base** — 完成
2. **AI Core Plan A** — 完成（LLM client, personality, usage, settings）
3. **AI Core Plan B** — 完成（Memory + Conversation Engine）
4. **AI Core Plan C** — 完成（Accessibility Service, UI automation, screen capture, virtual display）
5. **AI Core Plan D** — 完成（整合：background work, notification routing, rule engine）
6. **Plan D v2** — 完成（UI operation AIDL, smart notification, programmable events）
7. **Embedding System** — 設計完成（cloud + local ONNX）
8. **Character Pack System** — 設計完成
9. **AI Launcher** — 設計完成（Filament 3D）
10. **DollOS Protocol** — 設計中（手機 ↔ 電腦通訊）
11. **Voice Pipeline** — 未來（STT/TTS/Wake Word，依賴 DollOS-Server）
12. **System UI** — 未來（整合所有元件）

## 待辦

- **DollOS Protocol** — 手機與電腦端的通訊協定設計與實作
- **電源鍵長按 Push-to-Talk** — 需要修改 `PhoneWindowManager`，依賴 Voice Pipeline
- **Default Character Pack** — 內建在系統映像中的預設角色
- **Wake Word Detection** — 語音喚醒

## 重要決策記錄

- **GrapheneOS base** — 留在 GrapheneOS（AOSP 遷移已回退）
- **Sandboxed GMS** — GrapheneOS 自帶，OOBE 提供開關
- **GrapheneOS SetupWizard2 + InfoApp** — 已移除
- **系統色彩** — 預設 monochrome
- **AI Settings** — 原生整合在 Settings app（不是獨立 Activity）
- **DollOSAIService 架構** — 合併 repo（AIDL + Gradle + AOSP 整合在同一個 repo）
- **不實作 fallback** — 新介面直接取代，無舊路徑保留
- **UsageTracker** — 用 Room SQLite（不是 SharedPreferences）
- **LLM Client** — suspend API + coroutine delay retry（不是 Thread.sleep）
- **Memory search** — sqlite-vec + FTS5（取代 ObjectBox）
- **兩端架構** — 手機是外出時的身體，電腦是家（重度推理）
- **寫程式用 subagent 並行**
