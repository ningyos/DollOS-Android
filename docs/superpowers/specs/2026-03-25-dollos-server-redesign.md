# DollOS-Server Redesign — Design Spec

## Overview

DollOS-Server 是 DollOS AI 伴侶生態系統的電腦端。手機（DollOS-Android）是 AI 外出時的身體，電腦是 AI 的家 — 提供 GPU 驅動的推理、TTS、STT、Vision。兩端透過 DollOS Protocol 同步記憶，各自獨立運作。

本文件定義四項核心改動：記憶系統替換、工具執行遷移、角色系統整合、記憶同步協定。改動策略為外科手術式 — 保留 GuraOS 的 NATS IPC、Kmod v2、Driver 架構、CognitiveStack，只替換目標模組。

### 設計決策摘要

| 決策 | 結論 |
|------|------|
| 兩端關係 | 各自獨立運作，記憶同步 |
| 網路方案 | Protocol 層不管，使用者自行設定（Tailscale / DDNS / VPN） |
| 傳輸協定 | WebSocket + MessagePack（沿用 driver-phone） |
| 記憶系統 | memsearch — Markdown source of truth，sqlite-vec + FTS5 |
| 向量資料庫 | sqlite-vec（兩端統一，移除 Milvus） |
| 檔案儲存 | 本地檔案系統（容器內 `/data/dollos/`，volume mount） |
| 工具執行 | LLM native tool calling（移除 RestrictedPython） |
| 角色系統 | `.doll` 完整支援（人格 + 語音 + 3D） |
| 子代理 | 移除 TinyGura（主循環 + parallel tool calls 足夠） |
| 外部依賴 | 只保留 NATS（移除 Milvus、etcd、Attu、MinIO） |
| 改動策略 | 外科手術式，保留 GuraOS 核心架構 |

### 移除項目總覽

| 移除 | 原因 |
|------|------|
| `shell/executor.py`, `shell/guards.py` | RestrictedPython 沙箱，改用 native tool calling |
| `guraverse/` (TinyGura + AgentRegistry) | 子代理不再需要 |
| `services/learner.py` (LearnerService) | 依賴 TinyGura 經驗回流 |
| `infra/milvus.py` (MilvusVectorStore) | 改用 sqlite-vec |
| `infra/rustfs.py` (RustFS/MinIO client) | 改用本地檔案系統 |
| `docker/compose.milvus.yml` | Milvus + etcd + Attu |
| docker-compose 中的 MinIO service | RustFS |
| `pymilvus`, `minio` 依賴 | 不再使用 |
| `RestrictedPython` 依賴 | 不再使用 |
| AgentLoop 的 code block 偵測 + 迴圈偵測 | 不再需要 |
| ToolNamespace 的 thread proxy | 不再需要（沒有 daemon thread） |
| spawn_agent 相關工具 | TinyGura 移除 |

---

## Section 1: 記憶系統（memsearch）

### 設計原則

**Markdown 是 source of truth。** sqlite-vec + FTS5 索引是衍生物，可從 Markdown 重建。人類可以直接閱讀和編輯記憶檔案。

### 三層載入策略

| 層級 | 內容 | 載入方式 |
|------|------|---------|
| Tier 1 | `MEMORY.md` | 永遠載入 system prompt |
| Tier 2 | `YYYY-MM-DD.md` | 今天 + 昨天自動載入 |
| Tier 3 | `people/`, `topics/`, `decisions/` | 搜尋時載入 |

- **Tier 1** 是手動維護的索引/摘要，AI 可以更新但不會自動覆寫。類似 table of contents，指向 Tier 3 的詳細記憶。
- **Tier 2** 是每日記憶日誌，記錄當天重要事件和對話摘要。
- **Tier 3** 是按主題分類的深層記憶，靠 hybrid search 在需要時載入。

### 儲存結構

每個角色有獨立的記憶空間：

```
/data/dollos/memory/<character-id>/
  MEMORY.md              ← Tier 1：永遠載入
  2026-03-25.md          ← Tier 2：每日記憶
  2026-03-24.md
  people/                ← Tier 3：深層記憶
    master.md
    friend-a.md
  topics/
    music-preferences.md
    work-projects.md
  decisions/
    important-choice.md
  index/
    memory.db            ← sqlite-vec + FTS5（可重建）
```

### 搜尋

Hybrid search 結合兩種搜尋機制：

- **sqlite-vec** — 向量相似度搜尋（cosine similarity）
- **FTS5** — BM25 關鍵字搜尋
- **融合** — RRF（Reciprocal Rank Fusion），70% vector + 30% BM25
- **Embedding** — 透過 NATS 呼叫現有的 embedding kmod（Qwen3-Embedding）

### 索引建構

- Markdown 按 heading 分 chunk（最大 1500 字元）
- 每個 chunk 計算 SHA-256 content hash 作為 primary key
- 去重：相同 hash 不重新 embed
- 索引重建觸發：檔案新增/修改/刪除、索引損壞、服務啟動偵測不一致

### 寫入

三個觸發來源，全部進入單一 serialized write queue：

1. **Context threshold** — context 達 70-80% 時，壓縮前提取重要事實
2. **Idle background** — 對話閒置 N 分鐘後，背景整理記憶
3. **Event-based** — 使用者要求「記住這個」、對話結束

Write queue 特性：
- 序列化寫入，避免併發衝突
- 失敗重試 3 次
- 持久化到 `pending_writes.json`，服務重啟時重放
- 衝突時 Markdown 內容優先

### Consolidation

寫入時檢查現有記憶的相似度：
- ≥ 0.95：跳過（重複）
- ≥ 0.85：更新現有記憶
- < 0.85：新增記憶

### Context 注入

每次處理事件前：
1. 載入 `MEMORY.md`（Tier 1）
2. 載入今天 + 昨天的 daily log（Tier 2）
3. 用事件內容搜尋 Tier 3，注入相關結果
4. 全部作為 `[Memory]` section 放入 system prompt

### 與 GuraOS 的整合

- `MemoryService` 重寫底層：Milvus → sqlite-vec
- IPC 介面不變：`store` / `search` / `update` / `delete` / `list_entries` / `stats`
- 其他模組（GuraCore、tools、drivers）透過 IPC 操作，不受影響
- auto-recall 邏輯保留，改為讀取三層結構
- auto-memorization 改為寫 Markdown 檔案而非直接寫 vector store

### 圖片儲存

移除 RustFS/MinIO，改用本地檔案系統：
- 儲存路徑：`/data/dollos/images/<character-id>/`
- 檔名：SHA-256 content hash + 原始副檔名
- metadata 存在 sqlite-vec 同一個 DB（image_memory table）
- ImageMemory 的向量搜尋也走 sqlite-vec

---

## Section 2: 工具執行（Native Tool Calling）

### 核心改動

從 RestrictedPython code execution 遷移到 LLM native tool calling。

**認知循環：**
```
（現在）Think → Write <code> → RestrictedPython execute → Observe
（新的）Think → LLM tool_use → Framework dispatch → Observe
```

### AgentLoop 重寫

現有 `AgentLoop` 的 code block 偵測邏輯移除，改為：

1. 呼叫 LLM API，`tools` 參數傳入可用工具的 JSON schema
2. 讀取 response 中的 `tool_use` blocks
3. 對每個 tool_use：查找工具 → capability 檢查 → 執行 → 收集結果
4. 將所有 tool results 作為 `tool_result` 回傳 LLM
5. 重複直到 LLM 回傳純文字（無 tool_use）= 最終回覆

Parallel tool calls 由 LLM 自行決定 — 一次 response 可包含多個 tool_use。

### ToolNamespace 簡化

保留：
- 命名空間分組（`memory.*`、`task.*`、`platform.*`、`character.*`）
- Capability 檢查（CapabilityManager）
- 每平台工具可見性控制

移除：
- `run_coroutine_threadsafe` thread proxy（不再有 daemon thread）
- Python function signature 生成（改為 JSON tool schema）

新增：
- `to_tool_schemas()` — 產生 LLM API 的 tools 參數格式
- `dispatch(tool_name, arguments)` — 根據 namespace 路由到實際函數

### Kmod 工具

Kmod v2 的 `ToolDef` 已經是 `name` / `description` / `parameters`（JSON schema），直接轉換為 LLM tool schema，無需改動 kmod 端。

### TinyGura 及相關移除

- `guraverse/` 目錄（TinyGura runtime、AgentRegistry、AgentDefinition）
- `services/learner.py`（LearnerService、ExperienceEntry）
- spawn 相關工具：`spawn_agent`, `list_processes`, `kill_process`, `create_agent`, `delete_agent`, `get_agent_info`, `list_agents`
- `pc_agent`, `phone_agent` 工具（改為主循環直接操作）

### 影響範圍

| 檔案 | 改動 |
|------|------|
| `gura/agent_loop.py` | 重寫核心迴圈 |
| `gura/tools.py` | 移除 code execution 環境，改為 JSON schema |
| `gura/tool_namespace.py` | 簡化，移除 thread proxy |
| `gura/core.py` | 移除 `_spawn_tinygura`，簡化 `_process` |
| `shell/` | 整個目錄移除 |
| `guraverse/` | 整個目錄移除 |
| `services/learner.py` | 移除 |
| `tools/agents/` | 移除 spawn 相關工具 |
| 測試 | 所有 code block 測試重寫 |

---

## Section 3: 角色系統（.doll）

### .doll 檔案格式

`.doll` 是 zip archive，包含：

| 檔案 | 用途 |
|------|------|
| `manifest.json` | 角色 metadata（name, id, version, author） |
| `personality.json` | backstory, directive, dynamism, address, language |
| `voice.json` | TTS provider, reference audio path, voice ID |
| `scene.json` | 場景設定 |
| `model.glb` | 3D 模型（glTF 2.0） |
| `animations/` | 動畫檔案 |
| `thumbnail.png` | 角色縮圖 |
| `wake_word.bin` | 喚醒詞模型（可選） |

### 儲存

解壓到 `/data/dollos/characters/<character-id>/`，保持原始目錄結構。

### CharacterManager

| 方法 | 功能 |
|------|------|
| `load(doll_path)` | 解壓、驗證（zip-slip protection + size limit）、載入 |
| `switch(character_id)` | 切換當前角色（重載 system prompt + TTS + 3D） |
| `list()` | 列出已安裝角色 |
| `delete(character_id)` | 移除角色 + 記憶（需確認） |
| `export(character_id)` | 打包成 `.doll`（不含記憶） |
| `get_current()` | 取得當前角色資訊 |

不需要重啟服務即可切換角色。

### 整合點

**System Prompt：**
- 從 `personality.json` 讀取 backstory、directive、address、language
- 取代現有 config 裡硬編碼的人格設定
- dynamism 欄位映射到 LLM temperature

**TTS Kmod：**
- 從 `voice.json` 讀取 reference audio 路徑和 provider 設定
- 切換角色時通知 TTS kmod 更新語音設定（透過 NATS）

**Desktop Web UI：**
- 從 `model.glb` + `animations/` 載入 3D 模型
- 切換角色時通知 Web UI 更新（透過 WebSocket）

**memsearch：**
- `MEMORY.md`（Tier 1）注入角色名和人格摘要
- 每個角色有獨立記憶目錄

### 工具暴露

角色管理暴露為 native tool calling 工具：
- `character.list` — 列出可用角色
- `character.switch` — 切換角色
- `character.info` — 查看角色資訊

---

## Section 4: 記憶同步 + DollOS Protocol

### 連線

- 手機端主動連到電腦端的 WebSocket endpoint（沿用 driver-phone）
- 認證：TOTP 或 pre-shared key
- 重連：指數退避，斷線期間兩端各自獨立運作
- 網路方案由使用者自行設定（Tailscale / WireGuard / DDNS / port forwarding），Protocol 只需要一個可達的 URL

### Message 格式

WebSocket + MessagePack（沿用 driver-phone 現有格式），擴充 message type：

```
{
  "type": "<message_type>",
  "payload": { ... },
  "timestamp": <unix_ms>,
  "id": "<uuid>"
}
```

Message types：

| type | 方向 | 用途 |
|------|------|------|
| `chat` | 雙向 | 文字訊息 |
| `audio` | 雙向 | 語音資料（TTS/STT） |
| `memory_sync_request` | 雙向 | 請求記憶同步（附帶檔案清單） |
| `memory_sync_response` | 雙向 | 回傳差異檔案清單 |
| `memory_file` | 雙向 | 傳輸 Markdown 檔案內容 |
| `character_sync` | 雙向 | 傳輸 .doll 檔案 |
| `character_switch` | 雙向 | 通知對方角色已切換 |
| `status` | 雙向 | 心跳 / 狀態 |

### 記憶同步流程

**完整同步（連線建立時）：**

1. 雙方交換檔案清單：`{ path, sha256, mtime }` for each Markdown file
2. 比對差異：
   - 只存在一端 → 傳給另一端（`memory_file`）
   - 兩端都有但 hash 不同 → last-write-wins（mtime 較新的贏）
   - 兩端都有且 hash 相同 → 跳過
3. 傳輸變動的 Markdown 檔案
4. 兩端各自重建 sqlite-vec 索引（非同步，不阻塞）

**即時同步（連線中）：**

- 檔案變動時推送（debounce 5 秒，避免頻繁寫入觸發大量同步）
- 單向推送：變動端 → 另一端

**衝突處理：**

- 預設 last-write-wins（mtime 較新的覆寫）
- 如果 mtime 差距 ≤ 5 秒（幾乎同時修改），保留兩份：原檔 + `<filename>.conflict.md`
- AI 下次讀到 `.conflict.md` 時自行合併

### 角色同步

- 手機安裝新 `.doll` → 透過 `character_sync` 傳到電腦端
- 電腦端安裝新 `.doll` → 傳到手機端
- 傳輸的是原始 `.doll` zip，不是解壓後的目錄
- 切換角色時發送 `character_switch` 通知另一端同步切換

### 不在 Protocol 範圍內

| 功能 | 處理方式 |
|------|---------|
| 語音串流 | 手機端透過 driver-phone 直接呼叫電腦端 TTS/STT kmod |
| LLM 推理 | 兩端各自獨立（手機用雲端 API，電腦用本地/雲端） |
| 系統操控 | 手機端自己處理（DollOSService） |

---

## 資料目錄結構

所有持久化資料統一放在 `/data/dollos/`（容器內路徑，透過 volume mount 映射到 host）：

```
/data/dollos/
  characters/                          ← .doll 解壓後的角色檔
    <character-id>/
      manifest.json
      personality.json
      voice.json
      scene.json
      model.glb
      animations/
      thumbnail.png
  memory/                              ← 記憶（per character）
    <character-id>/
      MEMORY.md
      YYYY-MM-DD.md
      people/
      topics/
      decisions/
      index/
        memory.db
  images/                              ← 圖片記憶（per character）
    <character-id>/
      <sha256>.<ext>
  db/
    dollos.db                          ← 主資料庫（Tortoise ORM：cron, calendar, usage, logs, stack）
  config/
    dollos.yaml                        ← 服務設定
```

---

## 外部依賴（改動後）

**保留：**
- NATS — IPC bus、Kmod v2 discovery、driver 通訊

**移除：**
- Milvus + etcd + Attu — 向量搜尋改用 sqlite-vec
- MinIO (RustFS) — 檔案儲存改用本地檔案系統
- RestrictedPython — code execution 移除

**新增：**
- sqlite-vec — 向量搜尋（Python binding）

**Docker Compose 簡化後：**
- `docker compose up -d` → NATS only
- `docker compose --profile local-infer up -d` → + vLLM 推理服務
- `docker compose --profile kmod up -d` → + TTS/STT kmod
