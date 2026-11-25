# LumetroLauncher

基于无障碍服务的 Android 第三方桌面（Launcher），采用偏 Windows 11 / 锤子风格的交互：底部悬浮工作台 + 九键 T9 搜索面板。

由 [queuejw](https://github.com/queuejw) 移植而来，[oldhoho](https://github.com/oldhoho) 维护增强。

## ✨ 主要功能

### 🚀 底部工作台 (Workbench)
- 贴屏幕底部的悬浮条：1 个「最近任务」键 + 6 个应用槽，左右滑动翻页
- 点击图标启动/切换应用，当前前台应用高亮
- 长按图标 + 上滑 → 冻结/解冻应用，冻结应用带 ❄ 角标

### 🔍 全部应用列表（九键面板）
- 4 行九键键盘，T9 模糊搜索：拼音首字母 / 全拼 / 英文 / 中文
- 拼音首字母分组（A-Z）+ 字母索引跳转
- 点击启动应用；长按弹出菜单（隐藏应用、应用信息、冻结等）
- 数字键长按绑定：应用 / 快捷方式 / 自定义 Intent / 内置动作（屏幕取字、剪贴板取字、📌 Pin 池）；绑定后长按直接执行，未绑定时长按进入绑定弹窗
- 冻结 / 未冻结列表、隐藏与黑名单管理
- 「控制」键长按触发省电暗幕

### ✂️ 取字与内容处理
- 屏幕取字：无障碍全屏框选（或截图选字层）→ OCR / 无障碍文字 → 分词气泡面板，可点选词语复制、编辑、翻译、搜索、分享
- 剪贴板取字：读取刚复制的文字或图片，文字直接分词，图片走 OCR；不通过框选
- 链接雷达：框选后自动识别屏幕中的链接，点击即可按链接规则打开（支持多链接选择页）
- 图片编辑：裁剪、涂鸦、马赛克、文字、旋转、保存与分享
- Pin 池：把文字/图片钉在悬浮便签里，支持置顶、定时提醒、拖拽发送到其他应用；图片可悬浮显示、缩放旋转
- 搜索：可添加多个搜索引擎，支持直达链接、分享到应用、跳转指定页面、图片分享搜索

> 入口统一在工作台与九键：取字、Pin 池都可绑定到数字键长按，或从「工作台设置 → 屏幕内容与搜索」进入。系统快捷设置磁贴（QS Tile）已不再作为取字入口。

### 🤖 AI 会话（配合 CodexWeb）
> 启动器本身不包含 AI 模型，AI 能力由独立部署的 CodexWeb 服务提供；本应用是其 Android 端会话前端。详见下方「AI 与 CodexWeb 的协作方式」。

- AI 栏即会话输入框：自由输入、回车发送，点击即在同页展开多轮对话
- 会话历史以 CodexWeb 服务端为唯一真相：新建 / 切换 / 搜索会话，多端一致
- 消息气泡操作：复制、转发、收藏、删除、朗读、翻译、搜一搜；用户消息可「修改」（删除本轮及之后并用新文本重发），助手消息可「引用」回填输入框
- 设置面板与 Web 端同步：自动朗读、联网搜索、深度思考、工作模式、添加文件、能力开关与 MEMORY.md
- 工具箱按工作区 / 工具箱 / 系统分组，含终端、文件、抓包、提示词、全局搜索、上下文、诊断等；手机控制经 Shizuku 在本机执行
- 流式输出、后台朗读、Token 用量与命中率显示；同步轮询可按需开关
- 删除类操作均带二次确认；收藏与会话删除两端同步

### ❄️ 应用冻结
- 依赖 Shizuku（需单独安装）
- 冻结 / 解冻、冻结角标、列表批量管理

### 🌑 省电暗幕（OLED）
- 全屏半透明黑遮罩 + 挖洞
- 框选矩形（四角四边可拖拽），暗度可调

### 👆 手势
- 主页上滑 → 打开全部应用列表
- 主页下滑 → 展开通知面板
- 左侧手势条右滑 → 返回
- 音量键连按 3 次 → 打开面板

### 🎨 其他
- 使用系统壁纸作为桌面背景
- 图标包适配（ADW / GO Launcher / Nova）
- 面板背景自定义
- 崩溃 / 内存日志（写入应用私有目录，可在诊断页查看）

## 🤖 AI 与 CodexWeb 的协作方式

本项目**不内置大模型，也不直接调用任何厂商 API**。所有 AI 能力依赖你在同一网络下运行的 CodexWeb 服务；启动器只负责 Android 侧的输入、展示与设备操作。

### 连接与凭证
1. 在 CodexWeb 端生成带凭证的启动地址（`GET /api/launch-url`），形如 `http://<IP>:8123/?cxkey=<凭证>`。
2. 在启动器「工作台 → 工具箱 → 设置服务器」填入完整地址（含 `?cxkey=`）。凭证通过 `X-CX-Key` / `X-CX-Token` 请求头发送，不写入日志 URL。
3. 启动器通过 `GET /api/health` 检查连通性，未配置凭证时会明确提示。

### 数据与同步
| 能力 | 依赖的 CodexWeb 接口 | 说明 |
|------|----------------------|------|
| 会话管理 | `/api/sessions`、`/api/sessions/search`、`/api/sessions/meta` | 建会话、搜索、标题与元信息跟随 |
| 历史与增量 | `/api/history`、`/api/history/sig` | 服务端 history 是唯一真相；sig 轮询避免重复拉取 |
| 发送消息 | `/api/chat` + `/api/chat/stream` | SSE 推送 `message`/`stream`/`reasoning`/`tool`/`usage` 等事件；正文流式覆盖，过程折叠显示 |
| 修改与删除 | `/api/context/delete-from`、`/api/context/delete` | 修改＝删除本轮及之后再用新文本重发；删除整轮两端同步 |
| 偏好与收藏 | `/api/config`、`/api/capabilities`、`/api/memory`、`/api/skills/*` | 配置带 `rev`/`base_rev` 乐观锁，冲突时拉最新重试；与 Web 设置页共用 |
| 工具与文件 | `/api/exec`、`/api/files/*`、`/api/device/*` | 终端长任务、文件管理、设备能力探测 |

### 职责边界与注意
- **CodexWeb** 负责模型推理、会话落库、工具编排；**启动器**负责 Android 交互、后台朗读、以及通过 Shizuku 在本机执行设备动作。
- 手机控制的命令在**本机**执行，不依赖 CodexWeb App 在线。
- 连接断开时未完成的发送不会自动重发，网络恢复后需点气泡上的「重试」。
- Web 端删除/收藏会同步到启动器；删除类操作均有二次确认，避免误删。
- 详细改造记录见 [docs/AI_CHAT_REDESIGN.md](docs/AI_CHAT_REDESIGN.md)。

## 📋 权限说明

| 权限 | 用途 | 是否必须 |
|------|------|----------|
| 无障碍服务 | 工作台、手势控制 | ✅ 核心依赖 |
| 悬浮窗 (SYSTEM_ALERT_WINDOW) | 工作台 / 浮层显示 | ✅ 必须 |
| 使用情况访问 (PACKAGE_USAGE_STATS) | 读取应用使用情况 | ✅ 必须 |
| 应用列表 (QUERY_ALL_PACKAGES) | 枚举已安装应用 | ✅ 必须 |
| 通知权限 (POST_NOTIFICATIONS) | Pin 池定时提醒、图片通知 | ⚠️ 提醒功能需要 |
| 精确闹钟 (SCHEDULE_EXACT_ALARM) | Pin 池定时提醒准时触发 | ⚠️ 可选 |
| 管理所有文件 (MANAGE_EXTERNAL_STORAGE) | 壁纸、图标包等文件读写（日志已改为应用私有目录） | ⚠️ Android 11+ 需要 |
| Shizuku | 应用冻结、手机控制命令的本机执行 | ⚠️ 对应功能需要 |

## ⚠️ 注意事项
1. 首次启动需授予上述权限，否则部分功能不可用
2. 无障碍服务是核心依赖，必须开启
3. Shizuku 需单独安装，用于应用冻结与手机控制
4. 部分 ROM（如 vivo OriginOS）会频繁查杀无障碍服务，需把本应用加入白名单 / 开启后台保活
5. AI 会话需 CodexWeb 服务可达，启动器不内置模型；请按上文配置凭证地址

## 🔧 开发环境
- Kotlin + Gradle（AGP 8.5 / Gradle 8.7 / JDK 17）
- minSdk 24（Android 7.0），targetSdk / compileSdk 34
- 仓库已带 GitHub Actions 工作流（`.github/workflows/build.yml`）：push 到 `main` 或手动触发即构建 APK

## 📦 安装
1. 从 [Releases](https://github.com/oldhoho/LumetroLauncher3/releases) 下载 APK，或从 Actions 运行的 Artifacts 下载最新构建
2. 安装后授予所有权限，开启无障碍服务
3. 如需冻结功能或手机控制，安装 Shizuku
4. 如需使用 AI 会话，按上文「AI 与 CodexWeb 的协作方式」配置 CodexWeb 地址

## 📄 许可证
GNU General Public License v3.0（GPL-3.0），完整文本见 [LICENSE.txt](LICENSE.txt)。

- 原始 LumetroLauncher © queuejw，以 MIT 许可证发布；
- 屏幕内容子系统（取字、分词、图片编辑、链接雷达、Pin 池等）派生自 [GestureEVO](https://github.com/lumiagg/GestureEVO)（GPL-3.0），因此本项目整体以 GPL-3.0 发布。

---
**⚠️ 风险提示**：本应用需要较高权限（无障碍、悬浮窗、Shizuku），仅建议信任的用户使用。
