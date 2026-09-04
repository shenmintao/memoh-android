# Memoh Android

当前发布版本：`0.1.1`（versionCode 2）。

原生 Kotlin / Jetpack Compose 的 Memoh 聊天客户端。应用**不内置任何服务器地址**；首次启动和退出登录后必须手动输入 HTTPS 地址、用户名和密码。

## 功能

- 中文优先的 Material 3 界面：登录、机器人、会话、新建会话、聊天、刷新、退出。
- 自适应手机/平板布局：600 dp 起使用平板宽度约束和卡片网格，840 dp 起登录页使用双栏布局；聊天正文和输入区在大屏居中限宽，支持横竖屏切换。
- 登录后读取 `/users/me`、机器人、机器人设置、工作目录、会话和历史消息。
- 创建会话前选择工作目录；只有一个活动目录时自动选中，但仍可改为不绑定。
- 根据机器人设置创建默认 Agent / Pi（ACP 或直接运行时）会话：传递 `default_bot_agent_id`、运行时类型、项目元数据及工作目录。
- 每个聊天页面一个机器人级 OkHttp WebSocket，优先使用 `Authorization: Bearer` 请求头；仅当握手明确返回 401 时，刷新认证并进行一次 `?token=` 兼容回退。
- 支持断线重连、重新订阅、内存内 invocation/control 可靠重发、epoch/seq 缺口恢复、snapshot/delta、停止生成和工具批准/拒绝。
- 展示文本、思考、工具、错误、通知及未知块。最终文本使用 Markwon，不使用 WebView；未引入图片插件，因而不会加载远程 Markdown 图片。
- 回复等待期间使用 `dataSync` 前台服务。服务使用第二条**只读**订阅监控完成状态，不发送或重发用户消息。
- Android 13+ 在用户从可见界面发送时请求通知权限。

## 架构

项目是单 `app` 模块，采用手工依赖容器：

- `network/MemohApi.kt`：REST 和令牌到期前单飞刷新。
- `network/ChatSocket.kt`：WebSocket、可靠请求、重连/订阅。
- `network/RuntimeReducer.kt`：权威快照和严格 epoch/seq 增量归并。
- `security/TokenStore.kt`：AndroidKeyStore AES/GCM。
- `data/AppState.kt`：Compose 可观察 UI 状态和页面协调。
- `service/PendingReplyService.kt`：仅在回复待定时存活的前台监控服务。

首版没有引入 Room，以缩小可工作的构建面。机器人、会话、历史和流式文本仅在内存中，进程死亡后会从服务器重新获取；仅待处理的 `bot/session/invocation` 标识写入私有 SharedPreferences。令牌不在 Intent 或该状态文件中。服务重建只恢复只读订阅，绝不自动重新发送 prompt。草稿不保证跨进程保存。

## 配置与构建

要求 JDK 17+、Android SDK Platform 36，最低系统 API 26，目标 API 35。

创建本地且已被 `.gitignore` 忽略的 `local.properties`：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

Windows：

```powershell
$env:ANDROID_SDK_ROOT='C:\path\to\Android\Sdk'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 登录和 URL 规则

登录页地址默认为空，只给出非持久化的通用提示。输入可以省略 scheme，此时补为 HTTPS；末尾没有 `/api` 时补上，已有 `/api` 时不会重复。HTTP 明文地址被拒绝。只有登录成功后，规范化地址才与令牌一起加密保存。密码从不持久化。

## 权限与通知

- `INTERNET`：REST / WebSocket。
- `POST_NOTIFICATIONS`：Android 13+ 的完成提醒；拒绝后系统仍可按平台规则运行前台服务，但抽屉中的完成通知无法保证。
- `FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_DATA_SYNC`：用户点击“发送”后立即启动回复监控。

等待通知为低重要性、持续且不含消息正文，锁屏可见性为 secret。完成、错误或等待批准时发送通用通知，并深链回应用。没有常驻守护进程、开机广播、FCM、WakeLock。强制停止应用后 Android 不允许后台恢复；下次手动启动时只重新读取服务器历史，不会重发 prompt。

## 当前限制

- 用户输入请求会显示问题，但首版是只读的；工具批准/拒绝可操作。
- 可靠发送队列仅在进程内。服务/进程重启不会自动重发 prompt，这是防止重复消息的安全策略。
- 未实现附件、历史向前分页、会话删除/改名、编辑/重试和多并发待处理回复。
- WebSocket 始终先尝试 Authorization 请求头。部分代理/HTTP2 路径会错误拒绝该握手，因此仅在握手 401 后刷新令牌并回退一次 URL 查询参数。查询参数可能被 Memoh 当前请求日志、反向代理或基础设施 URL 日志记录；部署方应修复请求头路径并对 `token` 查询参数脱敏，此回退不会循环，也不会由客户端记录 URL。
- 深链通知携带 bot/session 标识，不携带令牌或消息正文；首版在进程已被杀死时会先回到登录/机器人页并正常恢复账户状态，而不会伪造发送恢复。

## 安全说明

- `allowBackup=false`，备份和设备迁移规则排除全部应用私有数据。
- AndroidKeyStore 内生成 AES 密钥，AES/GCM 使用随机 nonce 和固定版本 AAD；密钥失效或密文损坏会清除登录状态。
- 不保存密码，不把凭据放入 Room、Intent、通知或日志。
- 禁止明文流量，只信任系统 CA；没有 trust-all、宽松主机名验证、自签 TLS 绕过或证书固定。
- OkHttp 不安装请求/响应 body 日志拦截器，发布构建不输出认证、查询或正文。
- 不使用 WebView；Markwon core 不启用 HTML 插件和远程图片插件，并在渲染前阻止危险 URI scheme。

## Release 签名

项目不会生成或保存 keystore。发布签名仅从环境变量读取：

- `MEMOH_KEYSTORE_FILE`
- `MEMOH_KEYSTORE_PASSWORD`
- `MEMOH_KEY_ALIAS`
- `MEMOH_KEY_PASSWORD`

示例：

```powershell
$env:MEMOH_KEYSTORE_FILE='D:\secure\memoh-release.jks'
# 在安全终端或 CI secret 中设置其余变量
.\gradlew.bat assembleRelease
```

APK 自签名与 HTTPS 证书是两件事；不要为了自签 APK 而降低 TLS 验证。发布者应离线备份同一签名密钥，并用 `apksigner verify --verbose --print-certs` 验证产物。

## API 假设

REST base 是用户输入地址规范化后的 `/api`。远程 ACP 工作目录会按部署方对 Memoh v0.19 的补丁原样提交，不由 Android 客户端分类拒绝。实现依据同版本 Memoh Web/server 合约：`POST /auth/login`、`POST /auth/refresh`、`GET /users/me`、`GET /bots`、bot settings/workdirs/sessions/messages，以及 `/bots/{bot}/web/ws`。WebSocket 处理 `run_accepted`、`run_rejected`、`error`、`control_ack`、`runtime_snapshot`、`runtime_delta` 和 `runtime_dropped`。快照是权威状态；epoch 变化、序列缺口或 dropped 会触发重新订阅。
