# DollOS 專案狀態

> 這份文件用來讓 AI 助手在 context 重置後快速了解專案全貌和當前進度。

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
  ├── device/dollos/bluejay/             ← Pixel 6a device config
  ├── vendor/dollos/                     ← vendor overlay + SELinux
  └── build/make/                        ← AOSP build system（有修改）
```

## 自訂 Repo 一覽

| Repo | 路徑 | 用途 |
|------|------|------|
| `DollOSService` | `packages/apps/DollOSService` | 系統服務（Binder AIDL），action system，task manager |
| `DollOSSetupWizard` | `packages/apps/DollOSSetupWizard` | OOBE 9 頁設定精靈 |
| `vendor_dollos` | `vendor/dollos` | framework overlay、SELinux policy |
| `device_dollos_bluejay` | `device/dollos/bluejay` | Pixel 6a device makefile + SELinux |

## OOBE 頁面流程

```
welcome → theme → wifi → gms → model_download → api_key → personality → voice → complete
```

- `model_download`, `api_key` 可跳過
- `api_key` 跳過時直接到 `voice`
- `theme`: Light/Dark/Auto，用 UiModeManager 切換系統 night mode
- `gms`: sandboxed Google Play 開關
- `finishSetup()` 會設定 monochrome 系統色彩主題 + 標記 provisioned

## DollOSService 功能

- **AIDL 介面:** `IDollOSService`
  - API Key 管理、GMS opt-in、personality 設定
  - System action 執行、task manager
- **Action System:** OpenApp, SetAlarm, ToggleWiFi, ToggleBluetooth
- **Task Manager:** 電源鍵雙擊開啟，顯示 AI 任務列表

## Build 指令

```bash
cd ~/Projects/DollOS-build
source build/envsetup.sh
lunch dollos_bluejay-bp2a-userdebug
m -j$(nproc)                    # 全系統 build
m DollOSSetupWizard -j$(nproc)  # 只 build SetupWizard
```

**注意：** build 指令不能接 `| tail` 等 pipe，會導致 build 被 kill。背景執行用 `run_in_background`。

## 刷機流程

```bash
ADB=~/Projects/DollOS-build/out/host/linux-x86/bin/adb
$ADB root
$ADB remount          # 第一次需要 reboot 後再 remount
$ADB push out/target/product/bluejay/system_ext/priv-app/DollOSSetupWizard/DollOSSetupWizard.apk \
    /system_ext/priv-app/DollOSSetupWizard/DollOSSetupWizard.apk
$ADB reboot
```

## 框架 Overlay（vendor/dollos/overlay）

- 電源鍵雙擊 → Task Manager Activity
- 電源選單加入 AI activity 和 AI stop 按鈕

## 未 commit 改動（2026-03-22）

### DollOSSetupWizard（最多改動）
- **新增 ThemePage:** Light/Dark/Auto 主題選擇，用 `UiModeManager` API
- **Dark mode 支援:** 新增 `values-night/colors.xml`，adaptive color refs（`btn_primary_bg`, `btn_primary_text`, `divider`）
- **頁面狀態保存:** `onSaveInstanceState` 保存 ViewPager 位置，主題切換後不會回到第一頁
- **預設 monochrome:** `finishSetup()` 寫入 `theme_customization_overlay_packages` 設定 MONOCHROMATIC 主題
- 新增檔案：`res/layout/page_theme.xml`, `res/values-night/colors.xml`, `src/org/dollos/setup/ThemePage.kt`

### device/dollos/bluejay
- `dollos_bluejay.mk`: 加入 `SetupWizard2` 到 `PRODUCT_PACKAGES_REMOVE`

### build/make
- `handheld_system_ext.mk`: 移除 `SetupWizard2` from `PRODUCT_PACKAGES`

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

1. **DollOS Base** — 進行中（OOBE、device config、build system）
2. **AI Core** — 已設計，未開始實作（Plan A-D 已寫好）
3. **Avatar System** — 未來
4. **Voice Pipeline** — 未來（STT/TTS/Wake Word）
5. **Agent System** — 未來
6. **System UI** — 未來

## 重要決策記錄

- **GrapheneOS base 不遷移** — 曾嘗試遷移到純 AOSP，失敗後決定留在 GrapheneOS
- **Sandboxed GMS** — GrapheneOS 自帶，OOBE 提供開關
- **GrapheneOS SetupWizard2** — 已從 build 移除，用 DollOS 自己的 OOBE
- **系統色彩** — 預設 monochrome（黑白灰）
- **手勢導航** — 預設開啟
- **ADB + Developer Options** — userdebug build 預設開啟
