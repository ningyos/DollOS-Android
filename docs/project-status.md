# DollOS 專案狀態

> 這份文件用來讓 AI 助手在 context 重置後快速了解專案全貌和當前進度。
> 最後更新：2026-03-23

## 專案概述

DollOS 是一個基於 **GrapheneOS Android 16** 的 AI 伴侶手機作業系統。目標是在手機上運行一個有人格、有記憶、能操控系統的 AI 助手。

- **GitHub Org:** `https://github.com/ningyos/`
- **目標裝置:** Pixel 6a (bluejay)
- **版本:** 0.1.0
- **Base OS:** GrapheneOS（不是純 AOSP，遷移計畫已取消）

## 目錄結構

```
~/Projects/DollOS/              ← 主 repo（文件、設計、計畫）
~/Projects/DollOS-build/        ← GrapheneOS source tree（repo sync）
  ├── .repo/local_manifests/dollos.xml   ← DollOS 自訂 repo
  ├── packages/apps/DollOSService/       ← 系統服務
  ├── packages/apps/DollOSSetupWizard/   ← OOBE 設定精靈
  ├── frameworks/base/                   ← 有修改（電源選單 AI 按鈕）
  ├── device/dollos/bluejay/             ← Pixel 6a device config
  ├── vendor/dollos/                     ← vendor overlay + SELinux
  └── build/make/                        ← build system（移除 SetupWizard2）
```

## 自訂 Repo 一覽

| Repo | 路徑 | 用途 |
|------|------|------|
| `DollOSService` | `packages/apps/DollOSService` | 系統服務（Binder AIDL），action system，task manager |
| `DollOSSetupWizard` | `packages/apps/DollOSSetupWizard` | OOBE 9 頁設定精靈 |
| `vendor_dollos` | `vendor/dollos` | framework overlay（電源選單）、SELinux policy |
| `device_dollos_bluejay` | `device/dollos/bluejay` | Pixel 6a device makefile + SELinux |

**非 DollOS repo 但有修改：**
| Repo | 改動 |
|------|------|
| `frameworks/base` | GlobalActionsDialogLite 加入 AI Activity + AI Stop 按鈕、icon、strings |
| `build/make` | 從 PRODUCT_PACKAGES 移除 SetupWizard2 |

## OOBE 頁面流程

```
welcome → theme → wifi → gms → model_download → api_key → personality → voice → complete
```

- `model_download`, `api_key` 可跳過（`api_key` 跳過時直接到 `voice`）
- `theme`: Light/Dark/Auto，用 `UiModeManager` 切換系統 night mode，Activity 透過 `onSaveInstanceState` 保持頁面位置
- `gms`: sandboxed Google Play 開關（GrapheneOS 自帶）
- `finishSetup()` 會設定 monochrome 系統色彩主題 + 標記 provisioned

## DollOSService 功能

- **AIDL 介面:** `IDollOSService`
  - API Key 管理、GMS opt-in、personality 設定
  - System action 執行、task manager
- **Action System:** OpenApp, SetAlarm, ToggleWiFi, ToggleBluetooth
- **Task Manager Activity:** 從電源選單 AI Activity 按鈕開啟
  - 半透明 modal，底部卡片顯示 AI 任務列表
  - 點擊暗色背景或按返回鍵關閉
  - Resume All / Cancel 按鈕（待接上 DollOSAIService）

## 電源選單（frameworks/base 修改）

在 `GlobalActionsDialogLite.java` 加入兩個自訂按鈕：
- **AI Activity** (`aiactivity`) — 開啟 `TaskManagerActivity`，icon: `ic_ai_activity`（smart_toy）
- **AI Stop** (`aistop`) — 發送 `org.dollos.service.ACTION_AI_STOP` broadcast + toast，icon: `ic_ai_stop`（cancel）

Overlay 設定在 `vendor/dollos/overlay/frameworks/base/core/res/res/values/config.xml`。

## Build 指令

```bash
cd ~/Projects/DollOS-build
source build/envsetup.sh
lunch dollos_bluejay-bp2a-userdebug
m -j$(nproc)                    # 全系統 build
m DollOSSetupWizard -j$(nproc)  # 只 build SetupWizard
m DollOSService -j$(nproc)      # 只 build Service
m SystemUI -j$(nproc)           # 只 build SystemUI（電源選單改動）
```

**注意：** build 指令不能接 `| tail` 等 pipe，會導致 build 被 kill。背景執行用 `run_in_background`。

## 刷機流程

```bash
ADB=~/Projects/DollOS-build/out/host/linux-x86/bin/adb
$ADB root
$ADB remount          # 第一次需要 reboot 後再 remount

# 推送單一模組
$ADB push out/target/product/bluejay/system_ext/priv-app/DollOSSetupWizard/DollOSSetupWizard.apk \
    /system_ext/priv-app/DollOSSetupWizard/DollOSSetupWizard.apk

# 或同步整個 system 分區（frameworks/base 改動需要）
ANDROID_PRODUCT_OUT=~/Projects/DollOS-build/out/target/product/bluejay $ADB sync system
ANDROID_PRODUCT_OUT=~/Projects/DollOS-build/out/target/product/bluejay $ADB sync system_ext

$ADB reboot
```

## 設計文件

| 文件 | 內容 |
|------|------|
| `docs/superpowers/specs/2026-03-17-dollos-base-design.md` | DollOS Base 完整設計 |
| `docs/superpowers/specs/2026-03-19-ai-core-design.md` | AI Core 架構設計 |
| `docs/superpowers/plans/2026-03-17-dollos-base.md` | Base 實作計畫 |
| `docs/superpowers/plans/2026-03-19-ai-core-plan-a-*.md` | AI Service + LLM + Personality |
| `docs/superpowers/plans/2026-03-19-ai-core-plan-b-*.md` | Memory + Conversation |
| `docs/superpowers/plans/2026-03-19-ai-core-plan-c-*.md` | Agent + Emergency Stop |
| `docs/superpowers/plans/2026-03-19-ai-core-plan-d-*.md` | 整合計畫 |
| `docs/superpowers/plans/2026-03-22-oobe-theme-page.md` | OOBE 主題頁實作計畫 |

## 開發階段

1. **DollOS Base** — 基本完成
2. **AI Core** — 已設計，未開始實作（Plan A-D 已寫好）← 下一步
3. **Avatar System** — 未來
4. **Voice Pipeline** — 未來（STT/TTS/Wake Word）
5. **Agent System** — 未來
6. **System UI** — 未來

## 待辦

- **電源鍵長按 Push-to-Talk** — 需要修改 `PhoneWindowManager`，依賴 Voice Pipeline

## 重要決策記錄

- **GrapheneOS base 不遷移** — 曾嘗試遷移到純 AOSP，失敗後決定留在 GrapheneOS
- **Sandboxed GMS** — GrapheneOS 自帶，OOBE 提供開關
- **GrapheneOS SetupWizard2 + InfoApp** — 已從 build 和裝置移除
- **系統色彩** — 預設 monochrome（黑白灰），OOBE finishSetup 時寫入
- **手勢導航** — 預設開啟
- **ADB + Developer Options** — userdebug build 預設開啟
- **電源選單** — 加入 AI Activity 和 AI Stop 兩個按鈕
