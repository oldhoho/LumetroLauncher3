# AI 栏会话化改造设计

日期：2026-11-24
状态：已实施，assembleDebug 出包验证通过（2026-11-24 16:49）

## 实施说明（与设计稿的差异）

- 容器未改 rootContainer 类型，而是在其外层包 `FrameLayout`：内层保留原 LinearLayout（AI 条+键盘，键盘刷新逻辑零改动），对话浮层作为外层覆盖层，风险更低。
- `AiConsoleActivity` 未删除，而是复用 `AiChatOverlay` 改造为独立会话页（设置页入口仍可用），通过 `onClose` 回调区分「收起浮层」与「关闭页面」。
- `AiTasks.kt` 已删除；键盘 `AI_TASK` 绑定类型枚举保留（兼容旧绑定数据反序列化），但触发分支改为提示改用会话输入，绑定管理里的「AI 任务」入口已移除。
- SSE 文本映射：`stream` 增量追加、`message` 完整文本覆盖当前 assistant 气泡，避免重复；`reasoning`/`command`/`tool` 归入气泡内可折叠「过程」区。
- `end` 后延迟 400ms 拉 `/api/history` 覆盖本地乐观消息，保证多轮与服务端对齐。

## 1. 目标

把九键面板上方的 AI 栏从「快捷命令触发」改造成「正常会话输入框」：
- 用户在输入框里自由输入文字（系统输入法），回车发送；
- 一开始输入 / 点击输入框，对话界面在**同一页面内展开**，键盘落在对话输入框上，不遮挡九键；
- 对话是多轮会话，历史跟随 CodexWeb 的 session，多端一致。

## 2. 已确认的决策

| 维度 | 选择 |
| --- | --- |
| 展开形态 | 九键面板内浮层（不跳 Activity） |
| 常用任务 AiTasks | 完全移除 |
| 会话历史 | 跟随 CodexWeb session（后端 `/api/history`） |
| 输入方式 | 系统输入法自由输入 |

## 3. 现状（改造前）

- `AiTaskBar.kt`（`app/.../ru/queuejw/lumetro/ai/`）：状态点 + 状态文字 + `#T9输入` + 匹配到的常用任务芯片 + `›`。无自由文本输入。
- `AppListPanel.kt`：
  - 第 500 行创建 `AiTaskBar`，回调 `onOpenConsole`（跳 `AiConsoleActivity`）、`onSendTask`（直发预设指令）；
  - `handleT9Input()`（2441 行）把数字写入 `t9Input`，`updateAiBar()`（2373 行）调 `AiTasks.match()` 生成芯片；
  - `rootContainer` 第 487 行是 `LinearLayout(VERTICAL)`——**这是浮层改造的结构性障碍**。
- `AiConsoleActivity.kt`：全屏单任务控制台（服务器配置 + 单输入框 + 日志 + 最近总结 + 任务增删改），单任务模型，非消息列表。
- `AiConsoleClient.kt`：上一轮已适配 CodexWeb，对外 10 方法 + 7 状态字段；内部 `logText`/`summary` 是字符串，非结构化消息列表。

## 4. 目标架构

### 4.1 一栏两态

- **收起态**：AI 栏 = 状态点 + `EditText`（hint「输入消息，回车发送…」）+ 发送键。无芯片。
- **展开态**：对话浮层从底部升起覆盖九键，含消息气泡列表 + 底部输入框（与收起态同一输入源）+ 会话标题 + 收起键 + 设置入口。

### 4.2 结构容器改造（前置）

`AppListPanel.rootContainer` 由 `LinearLayout` 改为 `FrameLayout`（或外包一层 `FrameLayout`）：
- layer 0：AI 栏 + 键盘（现有内容）；
- layer 1：对话浮层（`GONE` ↔ `VISIBLE`，带位移动画）。

这样收起/展开是页面内状态切换，不销毁 `AiConsoleClient` 的会话与轮询。

### 4.3 会话数据层（`AiConsoleClient.kt`）

关键改动：从「单任务字符串」升级为「多轮会话」，且**服务端 history 为唯一真相**。

- 新增 `data class ChatMessage(role, content, ts)`；
- `sendTask(prompt)`：先追加一条本地 user 消息（乐观显示）→ POST `/api/chat` 拿 job → SSE 追加 assistant 消息（流式光标）；
- `on end`：以服务端 `/api/history` 刷新列表（本地乐观消息被真实历史覆盖），避免重复/错序；
- `refreshHistory()`：`GET /api/history?s=<session>`，映射 `messages[]` 的 `role`/`content` 到气泡；
- session 复用沿用 `ensureSession()`（`POST /api/sessions`，id 正则校验，失效重建）；
- `reasoning`/`command`/`tool` 等事件归入每条 assistant 消息的「过程」折叠区，不混进正文。

**无需客户端拼历史**：CodexWeb 的 `build_enrichment` 已把 `recent_history()` 注入 direct chat 上下文，同一 session 连续发送天然记得前文。

### 4.4 触发解耦（`AppListPanel.kt`）

- `handleT9Input()` 只做应用搜索，删除 `updateAiBar()` 调用；
- 删除 `AiTasks.match` 相关引用；
- AI 栏输入与九键数字彻底分离，避免搜应用时误触发对话。

### 4.5 完全移除 AiTasks

- 删除 `AiTasks.kt`（或保留文件但移除全部引用）；
- `AiTaskBar` 删除 `taskRow`/`taskScroll`/`setInput(tasks)`；
- `AiConsoleActivity` 的任务管理 UI 一并移除（若该 Activity 废弃则整文件删除）；
- 服务器配置入口迁入对话浮层的设置区（保留 `setServer` / `checkServer` 能力）。

## 5. CodexWeb 契约依据（已核实）

| 接口 | 用途 | 返回 |
| --- | --- | --- |
| `GET /api/history?s=<sid>` | 拉取会话历史 | `{session, turn_count, messages:[{role,content}]}`，role 为 `user`/`assistant` |
| `POST /api/sessions` | 建会话 | `{id: "s-..."}`，id 需匹配 `^s-\d{6}-\d{4}-[0-9a-z]{4}$` |
| `POST /api/chat` | 发消息 | body `{session,prompt,mode}` → `{job,...}` |
| `GET /api/chat/stream?job=` | SSE 流式 | `start/message/reasoning/command/files/tool/todo/usage/error/end` |
| `GET /api/health` | 连接检查 | `{ok, authenticated, codex, version}` |

鉴权：`X-CX-Key` / `X-CX-Token`（凭证来自 `?cxkey=` URL），延续上一轮适配。
历史细节：`recent_history()` 取最近 400 轮，每条 user_input + 第一条 `message` 事件的 text 作为 assistant 内容。

## 6. 交互流程

1. 点击 AI 栏输入框 → 浮层展开，焦点落到对话输入框，系统键盘弹出；
2. 用户输入文字，回车 → user 气泡入列（乐观）；
3. `sendTask` → SSE `message`/`stream` 事件流式填充 assistant 气泡；
4. `end` → 以 `/api/history` 刷新，列表与服务端对齐；
5. 点「收起」→ 浮层隐藏，回到迷你输入条，会话状态保留；
6. 再次展开 → 重新拉 `/api/history`，接着上次聊。

## 7. 文件改动清单

| 文件 | 改动 |
| --- | --- |
| `ai/AiTaskBar.kt` | 重写为输入条：EditText + 发送键 + 状态点；移除芯片；新增 `onExpand`/`onSend` 回调 |
| `components/core/sidebar/AppListPanel.kt` | rootContainer 改 FrameLayout；新增对话浮层；解耦 T9 输入；移除 AiTasks 引用；浮层内迁入服务器配置 |
| `ai/AiConsoleClient.kt` | 增加 ChatMessage 列表 + `refreshHistory()`；sendTask 改为会话语义；事件分流到「正文/过程」 |
| `ai/AiTasks.kt` | 删除（或清空引用） |
| `ai/AiConsoleActivity.kt` | 废弃或改造（服务器配置已迁出则删除） |
| `res/layout/` | 新增对话浮层布局（或纯代码构建，参照现有 AiTaskBar 风格） |

## 8. 风险与验证

- **系统键盘 vs 自定义九键面板**：EditText 聚焦时系统 IME 与自绘九键的 window 层级、`adjustResize` 需实测；必要时给对话浮层单独设 `softInputMode`。
- **多轮延迟**：每轮是独立 `codex exec` job，延迟与当前单次任务相当，非字对字实时聊天，需在文案上管理预期。
- **历史一致性**：`end` 后以服务端 history 覆盖，需处理 SSE 结束与 history 落盘的时序（必要时短延迟重试）。
- **验证**：改造后用 `./gradlew assembleDebug` 出包（环境已就绪），真机走「输入→展开→发送→流式→收起→再展开续聊」全链路。
